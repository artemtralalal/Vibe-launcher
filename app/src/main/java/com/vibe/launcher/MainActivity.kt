package com.vibe.launcher

import android.app.ActivityOptions
import android.app.WallpaperManager
import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.Context
import android.content.IntentFilter
import android.content.ComponentName
import android.content.res.Configuration
import android.content.Intent
import android.graphics.Color
import android.graphics.Outline
import android.graphics.RenderEffect
import android.graphics.Shader
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import com.vibe.launcher.BuildConfig
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.graphics.drawable.toBitmap
import androidx.core.view.drawToBitmap
import androidx.core.view.isVisible
import androidx.palette.graphics.Palette
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.switchmaterial.SwitchMaterial
import kotlin.math.abs
import kotlin.math.max

class MainActivity : AppCompatActivity() {

    private lateinit var prefs: LauncherPrefs
    private lateinit var root: FrameLayout
    private lateinit var homeContent: View
    private var wallpaperBlurView: ImageView? = null
    private var appWidgetHost: android.appwidget.AppWidgetHost? = null
    private val APPWIDGET_HOST_ID = 1024
    private val REQ_PICK_WIDGET = 7771
    private val REQ_CREATE_WIDGET = 7772
    private var pendingWidgetId: Int = -1
    private var widgetsRestored = false
    private lateinit var gridRecycler: RecyclerView
    private lateinit var dockBar: LinearLayout
    private lateinit var dockContainer: LinearLayout
    private lateinit var settingsButton: ImageButton
    private lateinit var quickPanel: View
    private lateinit var settingsOverlay: FrameLayout
    private lateinit var flagshipSwitch: SwitchMaterial
    private lateinit var blurSwitch: SwitchMaterial
    private lateinit var appDrawer: View
    private lateinit var drawerRecycler: RecyclerView
    private lateinit var drawerSearch: android.widget.EditText
    private lateinit var animationOverlay: View
    private lateinit var iconPackLabel: TextView
    private lateinit var pageIndicator: LinearLayout
    private lateinit var pageEdgeLeft: View
    private lateinit var pageEdgeRight: View

    private lateinit var gridAdapter: AppGridAdapter
    private lateinit var drawerAdapter: DrawerAdapter
    private var iosFolderOpen: String? = null
    private var iosJiggleMode = false
    private var iosPageVisible = false
    private val packageReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val action = intent?.action ?: return
            if (action == Intent.ACTION_PACKAGE_ADDED || action == Intent.ACTION_PACKAGE_REMOVED || action == Intent.ACTION_PACKAGE_REPLACED) {
                val pkg = intent.data?.schemeSpecificPart ?: return
                if (intent.getBooleanExtra(Intent.EXTRA_REPLACING, false) && action == Intent.ACTION_PACKAGE_REMOVED) return
                handlePackageChange(pkg, action)
            }
        }
    }
    private lateinit var iconPackManager: IconPackManager

    private val allApps = mutableListOf<AppEntry>()
    private val dockApps = arrayListOf<AppEntry?>(null, null, null, null)
    private val pages = mutableListOf<MutableList<AppEntry?>>()
    private var currentPageIndex = 0
    private var pageSize = 24
    private var lastLayoutMode: String = "portrait"

    private var draggedKey: String? = null
    private var draggedFromDock: Int? = null
    private var draggedFromDrawer = false
    private var draggedFromPage: Int = -1
    private var dragDropped = false
    private var pendingDropKey: String? = null
    private var pendingDropDockSlot: Int? = null
    private var pendingDropGridIndex: Int = -1
    private var pageSwitchRunnable: Runnable? = null
    private var pageDragActive = false
    private var pageDragDidSnap = false
    private var lastUnlockAnimAt = 0L
    private var pageDragStartX = 0f
    private var pageDragStartY = 0f
    private var pageDragDx = 0f
    private var dragGridOverlay: DragGridOverlay? = null
    private val folders = mutableMapOf<String, MutableList<String>>() // folderId -> appKeys
    private val folderNames = mutableMapOf<String, String>()
    private val folderTypes = mutableMapOf<String, String>() // normal, vertical, horizontal, big, superbig
    private var openFolderId: String? = null
    private var folderPage: Int = 0

    private val unlockReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == Intent.ACTION_USER_PRESENT) {
                stopAodOverlay()
                playUnlockSuperVideo()
                playUnlockAnimation()
            }
        }
    }
    private val screenOffReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == Intent.ACTION_SCREEN_OFF && prefs.aodEnabled) {
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M || Settings.canDrawOverlays(this@MainActivity)) {
                    startAodOverlay()
                }
            }
        }
    }

    private val wallpaperPickLauncher = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) showWallpaperTargetDialog(uri)
    }
    private val wallVideoPickLauncher = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            try { contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) } catch (_: Exception) {}
            prefs.wallVideoUri = uri.toString()
            applyWallpaperVideo()
            findViewById<TextView?>(R.id.wallVideoStatus)?.text = "Видео обоев выбрано"
        }
    }
    private val wallLockVideoPickLauncher = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            try { contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) } catch (_: Exception) {}
            prefs.wallLockVideoUri = uri.toString()
            findViewById<TextView?>(R.id.wallVideoStatus)?.text = "Видео разблокировки выбрано"
        }
    }
    private var pendingIconEditKey: String? = null
    private val iconPickLauncher = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        val key = pendingIconEditKey
        pendingIconEditKey = null
        if (uri != null && key != null) {
            try { contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) } catch (_: Exception) {}
            prefs.setIconOverride(key, uri.toString())
            loadApps()
        }
    }
    private var animStudioPreviewRef: AnimStudioPreview? = null
    private val animStudioIconPickLauncher = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            try {
                contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            } catch (_: Exception) {}
            try {
                val bmp = if (Build.VERSION.SDK_INT >= 28) {
                    val src = android.graphics.ImageDecoder.createSource(contentResolver, uri)
                    android.graphics.ImageDecoder.decodeBitmap(src) { d, _, _ -> d.isMutableRequired = false }
                } else {
                    @Suppress("DEPRECATION") android.provider.MediaStore.Images.Media.getBitmap(contentResolver, uri)
                }
                animStudioPreviewRef?.testIcon = bmp
                animStudioPreviewRef?.invalidate()
            } catch (_: Exception) {}
        }
    }

    private lateinit var volumePanel: View
    private var touchFxView: TouchFxView? = null
    private lateinit var audioManager: android.media.AudioManager
    private var volumeHideRunnable: Runnable? = null
    private val volumeReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == "android.media.VOLUME_CHANGED_ACTION") showVolumePanel()
        }
    }
    private val islandReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != "com.vibe.launcher.ISLAND_EVENT") return
            val state = intent.getStringExtra("state") ?: return
            if (state == "idle") {
                if (!prefs.islandAlways) {
                    islandState = "idle"
                    findViewById<View?>(R.id.dynamicIsland)?.post(islandHideRunnable)
                } else showIslandState("idle")
            } else {
                showIslandState(state)
                val title = intent.getStringExtra("title")
                if (!title.isNullOrBlank()) findViewById<TextView?>(R.id.dynamicIslandText)?.text = title.take(18)
            }
        }
    }

    private var lastLaunchKey: String? = null
    private var lastLaunchBounds: IntArray? = null
    private var lastLaunchColor: Int = Color.DKGRAY

    private data class AnimPoint(var x: Float, var y: Float, var strength: Float)
    private val animWinPoints = mutableListOf<AnimPoint>()
    private val animIconPoints = mutableListOf<AnimPoint>()
    private var animSimTarget: String = "window"
    private var animStudioAppKey: String? = null
    private var pendingLaunch = false
    private var openAnimating = false
    private var cameraGeniePending = false
    private var closeAnimator: android.animation.ValueAnimator? = null
    private var closeTargetView: View? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        try { androidx.core.view.WindowCompat.setDecorFitsSystemWindows(window, false) } catch (_: Exception) {}
        setContentView(R.layout.activity_main)

        prefs = LauncherPrefs(this)
        iconPackManager = IconPackManager(this)
        appWidgetHost = android.appwidget.AppWidgetHost(this, APPWIDGET_HOST_ID)
        root = findViewById(R.id.root)
        homeContent = findViewById(R.id.homeContent)
        try {
            val wp = ImageView(this).apply { scaleType = ImageView.ScaleType.CENTER_CROP; layoutParams = FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT) }
            try { wp.setImageDrawable(WallpaperManager.getInstance(this).drawable) } catch (_: Exception) {}
            root.addView(wp, 0)
            wallpaperBlurView = wp
        } catch (_: Exception) {}
        gridRecycler = findViewById(R.id.gridRecycler)
        dockBar = findViewById(R.id.dockBar)
        dockContainer = findViewById(R.id.dockContainer)
        settingsButton = findViewById(R.id.settingsButton)
        quickPanel = findViewById(R.id.quickPanel)
        settingsOverlay = findViewById(R.id.settingsOverlay)
        flagshipSwitch = findViewById(R.id.flagshipSwitch)
        blurSwitch = findViewById(R.id.blurSwitch)
        appDrawer = findViewById(R.id.appDrawer)
        drawerRecycler = findViewById(R.id.drawerRecycler)
        drawerSearch = findViewById(R.id.drawerSearch)
        animationOverlay = findViewById(R.id.animationOverlay)
        iconPackLabel = findViewById(R.id.iconPackLabel)
        pageIndicator = findViewById(R.id.pageIndicator)
        pageEdgeLeft = findViewById(R.id.pageEdgeLeft)
        pageEdgeRight = findViewById(R.id.pageEdgeRight)

        setupGrid()
        setupDrawer()
        setupDock()
        setupSettings()
        setupGestures()
        setupHomeCustomize()
        setupBack()
        setupPageEdges()
        setupVolumePanel()
        updateIslandVisibility()
        setupIslandInteractions()
        applyWallpaperVideo()
        setupAbout()
        applyOrientationLayout()
        loadFoldersFromPrefs()
        loadApps()
        setupWelcome()
        applyPowerSavingMode()
        applyVisualStyle()
        applyRefreshRatePref()
        applyFullscreenMode()
        applyScreenInsets()
        updateVirtualStatusBar()
        try { registerReceiver(unlockReceiver, IntentFilter(Intent.ACTION_USER_PRESENT)) } catch (_: Exception) {}
        try { registerReceiver(screenOffReceiver, IntentFilter(Intent.ACTION_SCREEN_OFF)) } catch (_: Exception) {}
        try { registerReceiver(volumeReceiver, IntentFilter("android.media.VOLUME_CHANGED_ACTION")) } catch (_: Exception) {}
        try {
            val pf = IntentFilter().apply { addAction(Intent.ACTION_PACKAGE_ADDED); addAction(Intent.ACTION_PACKAGE_REMOVED); addAction(Intent.ACTION_PACKAGE_REPLACED); addDataScheme("package") }
            registerReceiver(packageReceiver, pf)
        } catch (_: Exception) {}
        try {
            val f = IntentFilter("com.vibe.launcher.ISLAND_EVENT")
            if (Build.VERSION.SDK_INT >= 33) registerReceiver(islandReceiver, f, Context.RECEIVER_NOT_EXPORTED) else registerReceiver(islandReceiver, f)
        } catch (_: Exception) {}
    }

    override fun onDestroy() {
        try { unregisterReceiver(packageReceiver) } catch (_: Exception) {}
        try { unregisterReceiver(unlockReceiver) } catch (_: Exception) {}
        try { unregisterReceiver(screenOffReceiver) } catch (_: Exception) {}
        try { unregisterReceiver(volumeReceiver) } catch (_: Exception) {}
        try { unregisterReceiver(islandReceiver) } catch (_: Exception) {}
        try { unlockPlayer?.release() } catch (_: Exception) {}
        super.onDestroy()
    }

    override fun onResume() {
        super.onResume()
        try { appWidgetHost?.startListening() } catch (_: Exception) {}
        if (!widgetsRestored) { widgetsRestored = true; root.post { restoreWidgets() } }
        quickPanel.isVisible = false; settingsButton.isVisible = false
        quickPanel.isVisible = false
        settingsOverlay.isVisible = false
        if (draggedKey == null) appDrawer.isVisible = false
        if (cameraGeniePending) {
            cameraGeniePending = false
            playCameraGenieClose()
        } else if (pendingLaunch || openAnimating) {
            pendingLaunch = false
            playCloseAnimation()
        } else {
            closeTargetView?.alpha = 1f; closeTargetView = null
            clearBlur()
        }
    }

    override fun onPause() {
        try { appWidgetHost?.stopListening() } catch (_: Exception) {}
        super.onPause()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        try {
            val newMode = layoutMode()
            val modeChanged = newMode != lastLayoutMode && allApps.isNotEmpty()
            if (modeChanged) {
                persistLayoutForMode(lastLayoutMode)
                loadLayoutForMode(newMode)
                lastLayoutMode = newMode
                val flash = View(this)
                flash.setBackgroundColor(0xFF000000.toInt())
                flash.alpha = 0f
                root.addView(flash, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
                flash.animate().alpha(1f).setDuration(120).withEndAction {
                    applyOrientationLayout(); renderDock(); renderCurrentPage(); applyVisualStyle(); applyDrawerLayout(); applyFullscreenMode()
                    flash.animate().alpha(0f).setDuration(220).withEndAction { try { root.removeView(flash) } catch (_: Exception) {} }.start()
                }.start()
                return
            }
            applyOrientationLayout()
            renderDock()
            renderCurrentPage()
            applyVisualStyle()
            applyDrawerLayout()
            applyFullscreenMode()
        } catch (_: Exception) { }
    }

    private fun applyOrientationLayout() {
        val landscape = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        val homeContent = findViewById<LinearLayout>(R.id.homeContent)
        val workspace = findViewById<View>(R.id.workspaceContainer)
        val dockContainer = this.dockContainer
        val dockBar = this.dockBar
        val drawerBtn = findViewById<ImageButton>(R.id.drawerBtn)
        val d = resources.displayMetrics.density

        val cols = if (landscape) prefs.landscapeGridCols.coerceIn(4, 8) else prefs.portraitGridCols.coerceIn(3, 6)
        val rows = prefs.homeGridRows.coerceIn(4, 8)
        val newPageSize = (cols * rows).coerceIn(8, 48)
        val gridPad = if (landscape) prefs.landscapeGridPadding else prefs.portraitGridPadding
        val iconScale = if (landscape) prefs.landscapeIconScale.coerceIn(60, 120) / 100f else 1f
        val sideMargin = ((if (landscape) prefs.landscapeGridPadding else prefs.gridSideMargin) * d).toInt()

        if (newPageSize != pageSize) {
            pageSize = newPageSize
            gridAdapter.resize(pageSize)
            normalizePages()
        }

        val insetL = (prefs.screenInsetLeft * d).toInt()
        val insetT = (prefs.screenInsetTop * d).toInt()
        val insetR = (prefs.screenInsetRight * d).toInt()
        val insetB = (prefs.screenInsetBottom * d).toInt()
        val vsbExtra = if (prefs.virtualStatusBar) ((prefs.virtualStatusHeight.coerceIn(20,60) + 4) * d).toInt() else 0
        homeContent.setPadding(sideMargin + insetL, (24 * d).toInt() + insetT + vsbExtra, sideMargin + insetR, (20 * d).toInt() + insetB)
        gridRecycler.setPadding((gridPad * d).toInt(), (prefs.gridTopPadding * d).toInt(), (gridPad * d).toInt(), ((8 + prefs.gridBottomPadding) * d).toInt())
        val lm = gridRecycler.layoutManager
        if (lm is GridLayoutManager) {
            if (lm.spanCount != cols) gridRecycler.layoutManager = object : GridLayoutManager(this, cols) { override fun canScrollVertically() = false; override fun canScrollHorizontally() = false }
        } else {
            gridRecycler.layoutManager = object : GridLayoutManager(this, cols) { override fun canScrollVertically() = false; override fun canScrollHorizontally() = false }
        }
        gridRecycler.scaleX = iconScale
        gridRecycler.scaleY = iconScale
        gridRecycler.pivotX = gridRecycler.width / 2f
        gridRecycler.pivotY = 0f

        val indicator = findViewById<LinearLayout>(R.id.pageIndicator)
        val dockPos = currentDockPosition(landscape)
        val sideDock = dockPos in listOf("left", "right")
        if (sideDock) {
            homeContent.orientation = LinearLayout.HORIZONTAL
            (workspace.layoutParams as LinearLayout.LayoutParams).apply { width = 0; height = LinearLayout.LayoutParams.MATCH_PARENT; weight = 1f; bottomMargin = 0; marginEnd = (8*d).toInt(); marginStart = if (dockPos=="left") (8*d).toInt() else 0 }.also { workspace.layoutParams = it }
            (indicator.layoutParams as LinearLayout.LayoutParams).apply { width = 0; height = 0; weight = 0f }.also { indicator.layoutParams = it }
            indicator.isVisible = false
            val foldPortrait = !landscape && prefs.dockLayout == "orbit"
            (dockContainer.layoutParams as LinearLayout.LayoutParams).apply { width = LinearLayout.LayoutParams.WRAP_CONTENT; height = if (foldPortrait) LinearLayout.LayoutParams.WRAP_CONTENT else LinearLayout.LayoutParams.MATCH_PARENT; weight = 0f; topMargin = 0; marginEnd = 0; marginStart = 0 }.also { dockContainer.layoutParams = it }
            dockContainer.orientation = LinearLayout.VERTICAL
            dockContainer.gravity = android.view.Gravity.CENTER
            val sideW = if (foldPortrait) 76 else 88
            (dockBar.layoutParams as LinearLayout.LayoutParams).apply { width = (sideW * d).toInt(); height = if (foldPortrait) LinearLayout.LayoutParams.WRAP_CONTENT else 0; weight = if (foldPortrait) 0f else 1f }.also { dockBar.layoutParams = it }
            dockBar.orientation = LinearLayout.VERTICAL
            val pad = ((if (landscape) prefs.landscapeDockPadding else 8) * d).toInt()
            dockBar.setPadding(pad, pad, pad, pad)
            (drawerBtn.layoutParams as LinearLayout.LayoutParams).apply { marginStart = 0; topMargin = (8*d).toInt() }.also { drawerBtn.layoutParams = it }
            dockContainer.isVisible = true
            if (homeContent.indexOfChild(dockContainer) > homeContent.indexOfChild(workspace) && dockPos == "left") {
                homeContent.removeView(dockContainer); homeContent.addView(dockContainer, 0)
            } else if (homeContent.indexOfChild(dockContainer) < homeContent.indexOfChild(workspace) && dockPos == "right") {
                homeContent.removeView(dockContainer); homeContent.addView(dockContainer)
            }
        } else {
            homeContent.orientation = LinearLayout.VERTICAL
            // ensure dock is last in vertical layout
            if (homeContent.indexOfChild(dockContainer) != homeContent.childCount - 1) { homeContent.removeView(dockContainer); homeContent.addView(dockContainer) }
            (workspace.layoutParams as LinearLayout.LayoutParams).apply { width = LinearLayout.LayoutParams.MATCH_PARENT; height = 0; weight = 1f; marginEnd = 0; marginStart = 0; bottomMargin = 0 }.also { workspace.layoutParams = it }
            (indicator.layoutParams as LinearLayout.LayoutParams).apply { width = LinearLayout.LayoutParams.MATCH_PARENT; height = (20 * d).toInt(); weight = 0f }.also { indicator.layoutParams = it }
            indicator.isVisible = true
            dockContainer.orientation = LinearLayout.HORIZONTAL
            dockContainer.gravity = android.view.Gravity.CENTER_VERTICAL
            dockBar.orientation = LinearLayout.HORIZONTAL
            dockBar.setPadding((12*d).toInt(), 0, (12*d).toInt(), 0)
            (drawerBtn.layoutParams as LinearLayout.LayoutParams).apply { marginStart = (8*d).toInt(); topMargin = 0 }.also { drawerBtn.layoutParams = it }
            dockContainer.isVisible = true
            applyDockPlacement(landscape, dockPos, d)
        }
        val bs = (prefs.screenScale.coerceIn(50,200) / 100f)
        homeContent.post {
            homeContent.pivotX = homeContent.width / 2f
            homeContent.pivotY = homeContent.height / 2f
            homeContent.scaleX = bs
            homeContent.scaleY = bs
        }
        val ds = prefs.dockScale.coerceIn(70,140) / 100f
        dockContainer.scaleX = ds; dockContainer.scaleY = ds
        if (!dockMoveMode) {
            dockContainer.translationX = prefs.dockXOffset * d
            dockContainer.translationY = prefs.dockYOffset * d
        }
    }

    private fun currentDockPosition(landscape: Boolean): String {
        if (!landscape) {
            return when (prefs.dockLayout) {
                "harbor" -> if (prefs.dockCornerMode == "bottom-right") "bottom-right" else "bottom-left"
                "orbit" -> "right"
                else -> "bottom"
            }
        }
        return if (prefs.desktopMode) prefs.landscapeDockPosition else prefs.landscapeDockSide
    }

    private fun applyDockPlacement(landscape: Boolean, dockPos: String, d: Float) {
        val lp = dockContainer.layoutParams as LinearLayout.LayoutParams
        when (dockPos) {
            "bottom-left" -> {
                lp.width = LinearLayout.LayoutParams.WRAP_CONTENT
                lp.height = LinearLayout.LayoutParams.WRAP_CONTENT
                lp.weight = 0f; lp.topMargin = (4*d).toInt(); lp.marginStart = (8*d).toInt(); lp.marginEnd = 0
                lp.gravity = android.view.Gravity.START
                dockContainer.layoutParams = lp
                dockContainer.gravity = android.view.Gravity.START or android.view.Gravity.CENTER_VERTICAL
                (dockBar.layoutParams as LinearLayout.LayoutParams).apply { width = LinearLayout.LayoutParams.WRAP_CONTENT; height = (84*d).toInt(); weight = 0f }.also { dockBar.layoutParams = it }
            }
            "bottom-right" -> {
                lp.width = LinearLayout.LayoutParams.WRAP_CONTENT
                lp.height = LinearLayout.LayoutParams.WRAP_CONTENT
                lp.weight = 0f; lp.topMargin = (4*d).toInt(); lp.marginStart = 0; lp.marginEnd = (8*d).toInt()
                lp.gravity = android.view.Gravity.END
                dockContainer.layoutParams = lp
                dockContainer.gravity = android.view.Gravity.END or android.view.Gravity.CENTER_VERTICAL
                (dockBar.layoutParams as LinearLayout.LayoutParams).apply { width = LinearLayout.LayoutParams.WRAP_CONTENT; height = (84*d).toInt(); weight = 0f }.also { dockBar.layoutParams = it }
            }
            else -> { // bottom center or portrait
                if (!landscape || dockPos == "bottom") {
                    val widthPct = prefs.dockWidth.coerceIn(40,100) / 100f
                    lp.width = if (widthPct >= 0.99f) LinearLayout.LayoutParams.MATCH_PARENT else (resources.displayMetrics.widthPixels * widthPct).toInt()
                    lp.height = LinearLayout.LayoutParams.WRAP_CONTENT
                    lp.weight = 0f; lp.topMargin = (4*d).toInt(); lp.marginStart = 0; lp.marginEnd = 0
                    lp.gravity = android.view.Gravity.CENTER_HORIZONTAL
                    dockContainer.layoutParams = lp
                    dockContainer.gravity = android.view.Gravity.CENTER
                    val dockH = (prefs.dockHeight.coerceIn(56,140) * d).toInt()
                    (dockBar.layoutParams as LinearLayout.LayoutParams).apply { width = LinearLayout.LayoutParams.MATCH_PARENT; height = dockH; weight = 0f }.also { dockBar.layoutParams = it }
                }
            }
        }
    }

    private fun setupGrid() {
        gridAdapter = AppGridAdapter(
            onClick = { app, icon -> if (isFolderKey(app.key)) openFolder(folderIdFromKey(app.key)) else launchApp(app, icon, fromDrawer = false) },
            onLongPressDrag = { app, view -> showIconMenu(app, view, fromDock = null, fromDrawer = false) },
            onEmptyLongPress = { showSettingsButton() },
            shape = { prefs.iconShape },
            showLabels = { prefs.showLabels },
            cornerRadii = { currentCornerRadii() },
            iconScale = { prefs.iconScale },
            iconTintMode = { prefs.iconTintMode },
            iconTintColor = { prefs.iconTintColor },
            tiltEnabled = { prefs.tiltAnimation },
            tiltIntensity = { prefs.tiltIntensity }
        )
        gridRecycler.layoutManager = object : GridLayoutManager(this, prefs.portraitGridCols.coerceIn(3, 7)) { override fun canScrollVertically() = false; override fun canScrollHorizontally() = false }
        gridRecycler.adapter = gridAdapter
        gridRecycler.clipChildren = false
        gridRecycler.clipToPadding = false
        gridRecycler.setOnDragListener { _, event -> handleGridDrag(event) }

        val detector = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onLongPress(e: MotionEvent) {
                val child = gridRecycler.findChildViewUnder(e.x, e.y)
                if (child == null) showSettingsButton()
            }
        })
        gridRecycler.addOnItemTouchListener(object : RecyclerView.SimpleOnItemTouchListener() {
            override fun onInterceptTouchEvent(rv: RecyclerView, e: MotionEvent): Boolean {
                detector.onTouchEvent(e)
                return false
            }
        })
    }

    private fun setupDrawer() {
        drawerAdapter = DrawerAdapter(
            onClick = { app, icon -> launchApp(app, icon, fromDrawer = true) },
            onLongPress = { app, view -> showIconMenu(app, view, fromDock = null, fromDrawer = true) },
            shape = { prefs.iconShape },
            cornerRadii = { currentCornerRadii() },
            iconScale = { prefs.iconScale },
            iconTintMode = { prefs.iconTintMode },
            iconTintColor = { prefs.iconTintColor },
            tiltEnabled = { prefs.tiltAnimation },
            tiltIntensity = { prefs.tiltIntensity }
        )
        drawerRecycler.adapter = drawerAdapter
        applyDrawerLayout()
        appDrawer.setOnClickListener { }
        findViewById<ImageButton>(R.id.drawerSettingsBtn).setOnClickListener { showDrawerSettingsDialog() }
        drawerSearch.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                val q = s?.toString()?.trim()?.lowercase().orEmpty()
                val filtered = if (q.isEmpty()) allApps else allApps.filter { it.label.lowercase().contains(q) }
                drawerAdapter.submit(filtered)
                val ios = (prefs.drawerStyle == "ios" || prefs.drawerLayout == "ios")
                val searching = q.isNotEmpty()
                findViewById<View?>(R.id.iosLibraryContainer)?.isVisible = ios && !searching
                findViewById<View?>(R.id.drawerListContainer)?.isVisible = !ios || searching
                if (ios && !searching) buildIosLibrary()
            }
            override fun afterTextChanged(s: android.text.Editable?) {}
        })
        drawerSearch.setOnFocusChangeListener { v, hasFocus ->
            if (hasFocus) {
                v.animate().scaleX(1.02f).scaleY(1.02f).setDuration(180).start()
                if ((prefs.drawerStyle == "ios" || prefs.drawerLayout == "ios")) {
                    findViewById<View?>(R.id.iosLibraryContainer)?.animate()?.alpha(0f)?.translationY(40f)?.setDuration(180)?.withEndAction {
                        findViewById<View?>(R.id.iosLibraryContainer)?.isVisible = false
                        findViewById<View?>(R.id.iosLibraryContainer)?.alpha = 1f
                        findViewById<View?>(R.id.iosLibraryContainer)?.translationY = 0f
                    }?.start()
                    findViewById<View?>(R.id.drawerListContainer)?.apply { alpha = 0f; translationY = 40f; isVisible = true; animate().alpha(1f).translationY(0f).setDuration(220).start() }
                }
            } else {
                v.animate().scaleX(1f).scaleY(1f).setDuration(180).start()
                if ((prefs.drawerStyle == "ios" || prefs.drawerLayout == "ios") && drawerSearch.text.isNullOrBlank()) {
                    findViewById<View?>(R.id.drawerListContainer)?.isVisible = false
                    val lib = findViewById<View?>(R.id.iosLibraryContainer)
                    lib?.alpha = 0f; lib?.translationY = 40f; lib?.isVisible = true
                    lib?.animate()?.alpha(1f)?.translationY(0f)?.setDuration(220)?.start()
                    buildIosLibrary()
                }
            }
        }
    }

    private var dockMoveMode = false
    private var dockMoveStartX = 0f
    private var dockMoveStartY = 0f
    private var dockMoveOrigTx = 0f
    private var dockMoveOrigTy = 0f

    private fun setupDock() {
        dockBar.setOnDragListener { _, event -> handleDockDrag(event) }
        dockContainer.setOnLongClickListener {
            dockMoveMode = true
            dockMoveOrigTx = dockContainer.translationX
            dockMoveOrigTy = dockContainer.translationY
            dockContainer.animate().scaleX(dockContainer.scaleX * 1.05f).scaleY(dockContainer.scaleY * 1.05f).setDuration(120).start()
            android.widget.Toast.makeText(this, "Перетащи док в нужное место", android.widget.Toast.LENGTH_SHORT).show()
            true
        }
        val dockSwipe = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onFling(e1: MotionEvent?, e2: MotionEvent, velocityX: Float, velocityY: Float): Boolean {
                if (prefs.dockLayout != "harbor" || dockMoveMode) return false
                val dx = e2.x - (e1?.x ?: e2.x)
                if (abs(dx) < 80 || abs(dx) < abs(e2.y - (e1?.y ?: e2.y))) return false
                val toRight = dx > 0
                prefs.dockCornerMode = if (toRight) "bottom-right" else "bottom-left"
                dockContainer.animate().translationX(if (toRight) 40f else -40f).alpha(0.5f).setDuration(120).withEndAction {
                    applyOrientationLayout(); renderDock(); updateDockPreview()
                    dockContainer.translationX = if (toRight) -40f else 40f
                    dockContainer.alpha = 0.5f
                    dockContainer.animate().translationX(0f).alpha(1f).setDuration(180).start()
                }.start()
                return true
            }
        })
        var dockSwipeTracking = false
        dockContainer.setOnTouchListener { v, ev ->
            if (dockMoveMode) {
                when (ev.actionMasked) {
                    MotionEvent.ACTION_DOWN -> { dockMoveStartX = ev.rawX; dockMoveStartY = ev.rawY; true }
                    MotionEvent.ACTION_MOVE -> {
                        dockContainer.translationX = dockMoveOrigTx + (ev.rawX - dockMoveStartX)
                        dockContainer.translationY = dockMoveOrigTy + (ev.rawY - dockMoveStartY)
                        true
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        dockMoveMode = false
                        val cx = ev.rawX; val cy = ev.rawY
                        val sw = resources.displayMetrics.widthPixels; val sh = resources.displayMetrics.heightPixels
                        val pos = when {
                            cx < sw * 0.25f && cy > sh * 0.6f -> "bottom-left"
                            cx > sw * 0.75f && cy > sh * 0.6f -> "bottom-right"
                            cx < sw * 0.25f -> "left"
                            cx > sw * 0.75f -> "right"
                            else -> "bottom"
                        }
                        prefs.landscapeDockSide = pos; prefs.landscapeDockPosition = pos
                        if (prefs.dockLayout == "harbor" && (pos == "bottom-left" || pos == "bottom-right")) prefs.dockCornerMode = pos
                        dockContainer.translationX = 0f; dockContainer.translationY = 0f
                        updateLandDockLabel(); applyOrientationLayout(); renderDock(); renderCurrentPage(); updateDockPreview()
                        true
                    }
                    else -> false
                }
            } else {
                if (prefs.dockLayout == "harbor") {
                    if (ev.actionMasked == MotionEvent.ACTION_DOWN) {
                        dockSwipeTracking = !isPointOnAppIcon(ev.rawX, ev.rawY)
                    }
                    if (dockSwipeTracking) {
                        val consumed = dockSwipe.onTouchEvent(ev)
                        if (ev.actionMasked == MotionEvent.ACTION_UP || ev.actionMasked == MotionEvent.ACTION_CANCEL) dockSwipeTracking = false
                        consumed
                    } else false
                } else false
            }
        }
        renderDock()
    }

    private fun newPage(): MutableList<AppEntry?> = MutableList(pageSize) { null }

    private fun ensurePages() {
        if (pages.isEmpty()) pages.add(newPage())
        pages.forEach { p -> while (p.size < pageSize) p.add(null); if (p.size > pageSize) p.subList(pageSize, p.size).clear() }
    }

    private fun normalizePages() {
        ensurePages()
        // collect overflow items from pages that got trimmed
        val overflow = mutableListOf<AppEntry>()
        pages.forEach { p ->
            while (p.size > pageSize) {
                val it = p.removeAt(p.lastIndex)
                if (it != null) overflow.add(it)
            }
            while (p.size < pageSize) p.add(null)
        }
        overflow.forEach { app -> placeOverflowOnNextPage(app) }
        while (pages.size > 1 && pages.last().all { it == null }) pages.removeAt(pages.lastIndex)
        currentPageIndex = currentPageIndex.coerceIn(0, pages.lastIndex.coerceAtLeast(0))
    }

    private fun currentPageItems(): MutableList<AppEntry?> {
        ensurePages()
        currentPageIndex = currentPageIndex.coerceIn(0, pages.lastIndex)
        return pages[currentPageIndex]
    }

    private fun renderCurrentPage() {
        ensurePages()
        currentPageIndex = currentPageIndex.coerceIn(0, pages.lastIndex)
        if (!(prefs.drawerStyle == "ios" || prefs.drawerLayout == "ios")) iosPageVisible = false
        if (!iosPageVisible) {
            findViewById<View?>(R.id.iosHomeLibrary)?.isVisible = false
            gridRecycler.isVisible = true
        }
        gridAdapter.submitApps(pages[currentPageIndex].toList())
        updatePageIndicator()
        prefs.currentPage = currentPageIndex
    }

    private fun updatePageIndicator() {
        pageIndicator.removeAllViews()
        val count = pages.size.coerceAtLeast(1)
        val d = resources.displayMetrics.density
        val showIos = (prefs.drawerStyle == "ios" || prefs.drawerLayout == "ios")
        val total = count + if (showIos) 1 else 0
        for (i in 0 until total) {
            val isIosDot = showIos && i == count
            val active = if (isIosDot) iosPageVisible else (!iosPageVisible && i == currentPageIndex)
            val dot = View(this)
            val w = if (isIosDot) (14 * d).toInt() else ((if (active) 8 else 6) * d).toInt()
            val h = ((if (active) 8 else 6) * d).toInt()
            val lp = LinearLayout.LayoutParams(w, h)
            lp.marginStart = (3 * d).toInt(); lp.marginEnd = (3 * d).toInt()
            dot.layoutParams = lp
            val bg = android.graphics.drawable.GradientDrawable()
            bg.shape = if (isIosDot) android.graphics.drawable.GradientDrawable.RECTANGLE else android.graphics.drawable.GradientDrawable.OVAL
            if (isIosDot) bg.cornerRadius = h / 2f
            bg.setColor(if (active) prefs.accentColor else 0x66FFFFFF)
            dot.background = bg
            dot.setOnClickListener { if (isIosDot) showIosHomePage(true) else { if (iosPageVisible) hideIosHomePage(false); currentPageIndex = i; renderCurrentPage(); persistLayout() } }
            pageIndicator.addView(dot)
        }
        pageIndicator.isVisible = true
    }

    private fun showPageManager(moveKey: String? = null) {
        ensurePages()
        val names = pages.indices.map { "Страница ${it + 1}${if (it == currentPageIndex) " (текущая)" else ""}" }.toMutableList()
        names.add("＋ Создать страницу")
        if (moveKey == null && pages.size > 1) names.add("Удалить текущую страницу")
        AlertDialog.Builder(this)
            .setTitle(if (moveKey != null) "Переместить на страницу" else "Страницы")
            .setItems(names.toTypedArray()) { _, which ->
                when {
                    which < pages.size -> {
                        if (moveKey != null) moveAppToPage(moveKey, which) else { currentPageIndex = which; renderCurrentPage(); persistLayout() }
                    }
                    names[which].startsWith("＋") -> {
                        pages.add(newPage())
                        if (moveKey != null) moveAppToPage(moveKey, pages.lastIndex) else { currentPageIndex = pages.lastIndex; renderCurrentPage(); persistLayout() }
                    }
                    names[which].startsWith("Удалить") -> deleteCurrentPage()
                }
            }.show()
    }

    private fun moveAppToPage(key: String, targetPage: Int) {
        ensurePages()
        if (targetPage !in pages.indices) return
        var app: AppEntry? = dockApps.firstOrNull { it?.key == key }
        if (app == null) {
            for (p in pages) { app = p.firstOrNull { it?.key == key }; if (app != null) break }
        }
        if (app == null) app = findApp(key)
        if (app == null) return
        for (p in pages.indices) { pages[p] = pages[p].map { if (it?.key == key) null else it }.toMutableList() }
        for (i in dockApps.indices) if (dockApps[i]?.key == key) dockApps[i] = null
        val slot = pages[targetPage].indexOfFirst { it == null }
        if (slot >= 0) pages[targetPage][slot] = app else {
            val empty = pages[targetPage].indexOfFirst { it == null }
            if (empty >= 0) pages[targetPage][empty] = app
        }
        while (pages[targetPage].size < pageSize) pages[targetPage].add(null)
        if (pages[targetPage].size > pageSize) pages[targetPage] = pages[targetPage].take(pageSize).toMutableList()
        currentPageIndex = targetPage
        renderCurrentPage(); renderDock(); persistLayout()
    }

    private fun deleteCurrentPage() {
        ensurePages()
        if (pages.size <= 1) return
        val removed = pages.removeAt(currentPageIndex)
        currentPageIndex = currentPageIndex.coerceIn(0, pages.lastIndex)
        // move apps from removed page to current page empty slots
        for (app in removed.filterNotNull()) {
            val slot = pages[currentPageIndex].indexOfFirst { it == null }
            if (slot >= 0) pages[currentPageIndex][slot] = app
        }
        renderCurrentPage(); persistLayout()
    }

    private fun switchPage(delta: Int, fromDrag: Boolean = false) {
        ensurePages()
        val next = currentPageIndex + delta
        if (next < 0) return
        if (next > pages.lastIndex) {
            if (fromDrag) pages.add(newPage()) else return
        }
        if (next == currentPageIndex) return
        pages[currentPageIndex] = gridAdapter.allSlots().toMutableList()
        currentPageIndex = next
        renderCurrentPage()
    }

    private fun showIosHomePage(animated: Boolean = true) {
        if (!(prefs.drawerStyle == "ios" || prefs.drawerLayout == "ios")) return
        buildIosHomeLibrary()
        val lib = findViewById<View>(R.id.iosHomeLibrary)
        val w = findViewById<View>(R.id.workspaceContainer).width.toFloat().coerceAtLeast(1f)
        if (animated) {
            gridRecycler.animate().translationX(-w).alpha(0f).setDuration(150).withEndAction {
                gridRecycler.isVisible = false; gridRecycler.translationX = 0f; gridRecycler.alpha = 1f
                lib.translationX = w; lib.alpha = 0f; lib.isVisible = true
                lib.animate().translationX(0f).alpha(1f).setDuration(180).start()
            }.start()
        } else {
            gridRecycler.isVisible = false; lib.isVisible = true; lib.translationX = 0f; lib.alpha = 1f
        }
        iosPageVisible = true
        pageIndicator.isVisible = true
        updatePageIndicator()
    }

    private fun hideIosHomePage(animated: Boolean = true) {
        val lib = findViewById<View>(R.id.iosHomeLibrary)
        val w = findViewById<View>(R.id.workspaceContainer).width.toFloat().coerceAtLeast(1f)
        if (animated) {
            lib.animate().translationX(w).alpha(0f).setDuration(150).withEndAction {
                lib.isVisible = false; lib.translationX = 0f; lib.alpha = 1f
                gridRecycler.translationX = -w; gridRecycler.alpha = 0f; gridRecycler.isVisible = true
                gridRecycler.animate().translationX(0f).alpha(1f).setDuration(180).start()
            }.start()
        } else {
            lib.isVisible = false; gridRecycler.isVisible = true; gridRecycler.translationX = 0f; gridRecycler.alpha = 1f
        }
        iosPageVisible = false
        updatePageIndicator()
    }

    private fun switchPageAnimated(delta: Int) {
        ensurePages()
        if (iosPageVisible) {
            if (delta < 0) { hideIosHomePage(animated = true) }
            return
        }
        val iosMode = (prefs.drawerStyle == "ios" || prefs.drawerLayout == "ios") && prefs.iosLibraryOpenMode == "page"
        if (delta > 0 && iosMode && currentPageIndex == pages.lastIndex) { showIosHomePage(animated = true); return }
        val next = currentPageIndex + delta
        if (next > pages.lastIndex) return
        if (next < 0) return
        val w = gridRecycler.width.toFloat().coerceAtLeast(1f)
        val outX = if (delta > 0) -w else w
        val inX = -outX
        gridRecycler.animate().cancel()
        gridRecycler.animate().translationX(outX).alpha(0f).setDuration(150).withEndAction {
            switchPage(delta, fromDrag = false)
            gridRecycler.translationX = inX
            gridRecycler.alpha = 0f
            gridRecycler.animate().translationX(0f).alpha(1f).setDuration(180).start()
        }.start()
    }

    private fun setupPageEdges() {
        val listener = View.OnDragListener { v, event ->
            when (event.action) {
                android.view.DragEvent.ACTION_DRAG_ENTERED, android.view.DragEvent.ACTION_DRAG_LOCATION -> {
                    val dir = if (v.id == R.id.pageEdgeRight) 1 else -1
                    schedulePageSwitch(dir)
                    true
                }
                android.view.DragEvent.ACTION_DRAG_EXITED -> { cancelPageSwitch(); true }
                android.view.DragEvent.ACTION_DROP -> {
                    cancelPageSwitch()
                    try {
                        val key = event.clipData?.getItemAt(0)?.text?.toString() ?: return@OnDragListener true
                        val insertAt = if (v.id == R.id.pageEdgeRight) gridAdapter.itemCount else 0
                        pendingDropKey = key
                        pendingDropDockSlot = null
                        pendingDropGridIndex = insertAt
                        dragDropped = true
                    } catch (_: Exception) { }
                    true
                }
                android.view.DragEvent.ACTION_DRAG_ENDED -> { cancelPageSwitch(); resetDragState(); true }
                else -> true
            }
        }
        pageEdgeLeft.setOnDragListener(listener)
        pageEdgeRight.setOnDragListener(listener)
    }

    private fun setupAbout() {
        val cardBtn = findViewById<View>(R.id.aboutCardBtn)
        val aboutOverlay = findViewById<View>(R.id.aboutOverlay)
        val aboutCard = findViewById<View>(R.id.aboutCard)
        val aboutGradient = findViewById<View>(R.id.aboutGradientBg)
        val aboutVersion = findViewById<TextView>(R.id.aboutVersion)
        val aboutCardVersion = findViewById<TextView>(R.id.aboutCardVersion)
        val aboutDevice = findViewById<TextView>(R.id.aboutDevice)
        val devOverlay = findViewById<View>(R.id.devOverlay)
        val ver = try { packageManager.getPackageInfo(packageName, 0).versionName ?: "1.9.3-beta" } catch (_: Exception) { "1.9.3-beta" }
        aboutCardVersion.text = ver
        var devTaps = 0
        aboutCardVersion.setOnClickListener {
            devTaps++
            if (devTaps >= 7 && !prefs.devMode) {
                prefs.devMode = true
                android.widget.Toast.makeText(this, "Режим разработчика включён", android.widget.Toast.LENGTH_SHORT).show()
                findViewById<SwitchMaterial>(R.id.flagshipSwitch).isVisible = true
            }
        }
        aboutVersion.text = "Version $ver"
        val dm = resources.displayMetrics
        val screen = "${dm.widthPixels}x${dm.heightPixels} @${dm.densityDpi}dpi"
        val soc = if (Build.VERSION.SDK_INT >= 31) (Build.SOC_MODEL ?: Build.HARDWARE ?: "unknown") else (Build.HARDWARE ?: "unknown")
        val bt = try { packageManager.hasSystemFeature(android.content.pm.PackageManager.FEATURE_BLUETOOTH) } catch (_: Exception) { false }
        val accel = try { (getSystemService(android.content.Context.SENSOR_SERVICE) as android.hardware.SensorManager).getDefaultSensor(android.hardware.Sensor.TYPE_ACCELEROMETER) != null } catch (_: Exception) { false }
        val gyro = try { (getSystemService(android.content.Context.SENSOR_SERVICE) as android.hardware.SensorManager).getDefaultSensor(android.hardware.Sensor.TYPE_GYROSCOPE) != null } catch (_: Exception) { false }
        aboutDevice.text = "${Build.MANUFACTURER} ${Build.MODEL}\nAndroid ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})\nCPU/SoC: $soc\nЭкран: $screen\nBluetooth: ${if (bt) "да" else "нет"} • Акселерометр: ${if (accel) "да" else "нет"} • Гироскоп: ${if (gyro) "да" else "нет"}"

        fun openAbout() {
            aboutOverlay.isVisible = true
            aboutOverlay.alpha = 0f
            aboutOverlay.animate().alpha(1f).setDuration(220).start()
            val c1 = prefs.accentColor
            val c2 = Color.argb(255, (Color.red(c1)*0.4f).toInt(), (Color.green(c1)*0.4f).toInt(), (Color.blue(c1)*0.4f).toInt())
            aboutGradient.background = android.graphics.drawable.GradientDrawable(
                android.graphics.drawable.GradientDrawable.Orientation.TL_BR,
                intArrayOf(c2, 0xFF10131A.toInt(), c1 and 0x55FFFFFF)
            )
        }
        cardBtn.setOnClickListener { openAbout() }
        aboutCard.setOnClickListener { /* expand feel - already full overlay */ }
        findViewById<View>(R.id.aboutCloseBtn).setOnClickListener {
            aboutOverlay.animate().alpha(0f).setDuration(180).withEndAction { aboutOverlay.isVisible = false; aboutOverlay.alpha = 1f }.start()
        }
        findViewById<View>(R.id.aboutGithubBtn).setOnClickListener {
            try { startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse("https://github.com/artemtralalal/Vibe-launcher"))) } catch (_: Exception) {}
        }
        aboutVersion.setOnClickListener {
            val input = android.widget.EditText(this)
            input.hint = "Code"
            AlertDialog.Builder(this).setTitle("Developer mode").setView(input)
                .setPositiveButton("OK") { _, _ ->
                    if (input.text.toString().trim() == "2013") {
                        prefs.devMode = true
                        devOverlay.isVisible = true
                    }
                }.setNegativeButton("Cancel", null).show()
        }
        findViewById<View>(R.id.devCloseDevBtn).setOnClickListener { devOverlay.isVisible = false }
        findViewById<View>(R.id.devIconBehaviorBtn).setOnClickListener {
            prefs.iconPressAnim = !prefs.iconPressAnim
            updateDevLabels()
        }
        findViewById<View>(R.id.devCloseStyleBtn).setOnClickListener {
            android.widget.Toast.makeText(this, "Morph to icon: ON", android.widget.Toast.LENGTH_SHORT).show()
        }
        updateDevLabels()
    }

    private fun updateDevLabels() {
        val b = findViewById<Button>(R.id.devIconBehaviorBtn)
        b.text = if (prefs.iconPressAnim) "Press scale + fly (ON)" else "Press scale + fly (OFF)"
    }

    private var islandState: String = "idle"
    private var islandExpandedState: Boolean = false

    private fun updateIslandVisibility() {
        val island = findViewById<View?>(R.id.dynamicIsland) ?: return
        island.isVisible = false
    }

    private fun updateIslandLayout() {
        val island = findViewById<View?>(R.id.dynamicIsland) ?: return
        val compact = findViewById<View?>(R.id.islandCompact) ?: return
        val text = findViewById<TextView?>(R.id.dynamicIslandText)
        val d = resources.displayMetrics.density
        val baseW = (80f + prefs.islandW.coerceIn(20,200) * 0.8f) * d
        val baseH = (28f + prefs.islandH.coerceIn(20,200) * 0.25f) * d
        val w = if (prefs.islandRightHand && !islandExpandedState) baseH else baseW
        val h = if (prefs.islandRightHand && !islandExpandedState) baseW else baseH
        val lp = island.layoutParams as? FrameLayout.LayoutParams ?: return
        lp.width = FrameLayout.LayoutParams.WRAP_CONTENT; lp.height = FrameLayout.LayoutParams.WRAP_CONTENT
        lp.gravity = if (prefs.islandRightHand) android.view.Gravity.END or android.view.Gravity.TOP else android.view.Gravity.TOP or android.view.Gravity.CENTER_HORIZONTAL
        lp.topMargin = ((8 + prefs.islandY.coerceIn(0,200) * 0.8f) * d).toInt()
        lp.marginEnd = if (prefs.islandRightHand) ((8 + prefs.islandX.coerceIn(0,200) * 0.5f) * d).toInt() else 0
        lp.marginStart = if (!prefs.islandRightHand) (((prefs.islandX.coerceIn(0,200)-100) * 1.2f) * d).toInt() else 0
        island.layoutParams = lp
        val clp = compact.layoutParams
        clp.width = w.toInt(); clp.height = h.toInt()
        compact.layoutParams = clp
        compact.background = islandBgForState(islandState, h/2f)
        text?.textSize = if (prefs.islandRightHand) 11f else 12f
        if (prefs.islandRightHand && !islandExpandedState) {
            compact.rotation = 90f; text?.rotation = -90f
        } else { compact.rotation = 0f; text?.rotation = 0f }
        val expanded = findViewById<View?>(R.id.islandExpanded)
        expanded?.isVisible = islandExpandedState
        compact.isVisible = !islandExpandedState
    }

    private fun islandBgForState(state: String, radius: Float): android.graphics.drawable.GradientDrawable {
        val d = resources.displayMetrics.density
        return android.graphics.drawable.GradientDrawable().apply {
            cornerRadius = radius
            orientation = android.graphics.drawable.GradientDrawable.Orientation.LEFT_RIGHT
            when (state) {
                "unlock" -> colors = intArrayOf(0xFF101010.toInt(), 0xFF1E8E3E.toInt())
                "music" -> colors = intArrayOf(0xFF101010.toInt(), 0xFFFF8C00.toInt())
                "flash" -> colors = intArrayOf(0xFF101010.toInt(), 0xFFFFE082.toInt())
                "call" -> colors = intArrayOf(0xFF1E8E3E.toInt(), 0xFF101010.toInt(), 0xFFD93025.toInt())
                "record" -> colors = intArrayOf(0xFF101010.toInt(), 0xFFD93025.toInt())
                else -> { setColor(0xFF101010.toInt()) }
            }
            setStroke((1.5f*d).toInt(), 0x66FFFFFF)
        }
    }

    private fun showIslandState(state: String) {
        val island = findViewById<View?>(R.id.dynamicIsland) ?: return
        val compact = findViewById<View?>(R.id.islandCompact) ?: return
        val text = findViewById<TextView?>(R.id.dynamicIslandText) ?: return
        val icon = findViewById<View?>(R.id.islandIcon)
        islandState = state
        prefs.islandEnabled = true
        island.isVisible = true
        island.alpha = 1f
        islandExpandedState = false
        findViewById<View?>(R.id.islandExpanded)?.isVisible = false
        compact.isVisible = true
        val bg = islandBgForState(state, compact.height.coerceAtLeast(1) / 2f)
        compact.background = bg
        when (state) {
            "unlock" -> { text.text = "Разблокировано"; icon?.setBackgroundColor(0xFF1E8E3E.toInt()) }
            "music" -> { text.text = "Музыка"; icon?.setBackgroundColor(0xFFFF8C00.toInt()) }
            "flash" -> { text.text = "Фонарик"; icon?.setBackgroundColor(0xFFFFE082.toInt()) }
            "call" -> { text.text = "Звонок"; icon?.setBackgroundColor(0xFF1E8E3E.toInt()) }
            "record" -> { text.text = "Запись"; icon?.setBackgroundColor(0xFFD93025.toInt()) }
            else -> { text.text = "Vibe"; icon?.setBackgroundColor(0x55FFFFFF) }
        }
        updateIslandLayout()
        compact.scaleX = 0.92f; compact.scaleY = 0.92f
        compact.animate().scaleX(1f).scaleY(1f).setDuration(220).setInterpolator(OvershootInterpolator(1.1f)).start()
        if (!prefs.islandAlways && state != "idle") {
            island.removeCallbacks(islandHideRunnable)
            island.postDelayed(islandHideRunnable, 3000)
        }
    }

    private val islandHideRunnable = Runnable {
        if (!prefs.islandAlways && !islandExpandedState) {
            val island = findViewById<View?>(R.id.dynamicIsland) ?: return@Runnable
            island.animate().alpha(0f).setDuration(250).withEndAction {
                island.isVisible = prefs.islandAlways
                island.alpha = 1f
                if (prefs.islandAlways) showIslandState("idle")
            }.start()
        }
    }

    private fun setupIslandInteractions() {
        val compact = findViewById<View?>(R.id.islandCompact) ?: return
        val expanded = findViewById<View?>(R.id.islandExpanded) ?: return
        val island = findViewById<View?>(R.id.dynamicIsland) ?: return
        compact.setOnClickListener {
            when (islandState) {
                "flash" -> toggleFlashlight()
                "music" -> expandIsland()
                "call" -> expandIsland()
                "record" -> expandIsland()
                else -> if (prefs.islandAlways) expandIsland()
            }
        }
        compact.setOnLongClickListener {
            val intensity = prefs.islandPress.coerceIn(0,100) / 100f
            compact.cameraDistance = resources.displayMetrics.density * 5000f
            compact.animate().rotationXBy(-8f * intensity).scaleX(0.96f).scaleY(0.96f).setDuration(120).withEndAction {
                compact.animate().rotationX(0f).scaleX(1f).scaleY(1f).setDuration(180).start()
                expandIsland()
            }.start()
            true
        }
        findViewById<Button?>(R.id.islandAction2)?.setOnClickListener { collapseIsland() }
        findViewById<Button?>(R.id.islandAction1)?.setOnClickListener {
            when (islandState) {
                "flash" -> toggleFlashlight()
                "music" -> { try { val i = Intent("com.android.music.musicservicecommand"); i.putExtra("command","togglepause"); sendBroadcast(i) } catch (_: Exception) {} }
                "call" -> collapseIsland()
                else -> collapseIsland()
            }
        }
    }

    private fun expandIsland() {
        val island = findViewById<View?>(R.id.dynamicIsland) ?: return
        val compact = findViewById<View?>(R.id.islandCompact) ?: return
        val expanded = findViewById<View?>(R.id.islandExpanded) ?: return
        islandExpandedState = true
        compact.isVisible = false
        expanded.isVisible = true
        expanded.alpha = 0f; expanded.scaleX = 0.92f; expanded.scaleY = 0.92f
        expanded.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(220).setInterpolator(DecelerateInterpolator(1.5f)).start()
        val title = findViewById<TextView?>(R.id.islandExpandedTitle)
        val sub = findViewById<TextView?>(R.id.islandExpandedSub)
        val a1 = findViewById<Button?>(R.id.islandAction1)
        when (islandState) {
            "music" -> { title?.text = "Музыка"; sub?.text = "Управление воспроизведением"; a1?.text = "Пауза/Плей" }
            "flash" -> { title?.text = "Фонарик"; sub?.text = "Нажмите, чтобы выключить"; a1?.text = "Выкл" }
            "call" -> { title?.text = "Звонок"; sub?.text = "Быстрые действия"; a1?.text = "Сбросить" }
            "record" -> { title?.text = "Запись экрана"; sub?.text = "Идёт запись"; a1?.text = "Ок" }
            else -> { title?.text = "Dynamic Island"; sub?.text = "Нет активных событий"; a1?.text = "Ок" }
        }
        updateIslandLayout()
    }

    private fun collapseIsland() {
        val compact = findViewById<View?>(R.id.islandCompact) ?: return
        val expanded = findViewById<View?>(R.id.islandExpanded) ?: return
        islandExpandedState = false
        expanded.animate().alpha(0f).scaleX(0.96f).scaleY(0.96f).setDuration(180).withEndAction {
            expanded.isVisible = false; compact.isVisible = true; compact.alpha = 1f
            updateIslandLayout()
        }.start()
    }

    private var flashlightOn = false
    private fun toggleFlashlight() {
        try {
            val cm = getSystemService(Context.CAMERA_SERVICE) as android.hardware.camera2.CameraManager
            val id = cm.cameraIdList.firstOrNull() ?: return
            flashlightOn = !flashlightOn
            cm.setTorchMode(id, flashlightOn)
            if (flashlightOn) showIslandState("flash") else { showIslandState("idle"); if (!prefs.islandAlways) findViewById<View?>(R.id.dynamicIsland)?.postDelayed(islandHideRunnable, 800) }
        } catch (_: Exception) {
            android.widget.Toast.makeText(this, "Фонарик недоступен", android.widget.Toast.LENGTH_SHORT).show()
        }
    }

    private fun playUnlockSuperVideo() {
        try {
            val uriStr = prefs.wallLockVideoUri
            if (uriStr.isBlank()) return
            val vv = findViewById<android.widget.VideoView?>(R.id.wallpaperVideo) ?: return
            val uri = android.net.Uri.parse(uriStr)
            vv.setVideoURI(uri)
            vv.isVisible = true
            vv.alpha = 1f
            vv.setOnPreparedListener { mp -> mp.isLooping = false; mp.setVolume(0f,0f); vv.start() }
            vv.setOnCompletionListener { applyWallpaperVideo(); vv.alpha = 1f }
        } catch (_: Exception) {}
    }

    private fun applyWallpaperVideo() {
        try {
            val vv = findViewById<android.widget.VideoView?>(R.id.wallpaperVideo) ?: return
            val uriStr = prefs.wallVideoUri
            if (uriStr.isBlank()) { vv.stopPlayback(); vv.isVisible = false; return }
            val uri = android.net.Uri.parse(uriStr)
            vv.setVideoURI(uri)
            vv.setOnPreparedListener { mp -> mp.isLooping = true; mp.setVolume(0f,0f); vv.start() }
            vv.isVisible = true
        } catch (_: Exception) {}
    }

    private fun setupVolumePanel() {
        volumePanel = findViewById(R.id.volumePanel)
        touchFxView = findViewById(R.id.touchFxView)
        touchFxView?.isVisible = prefs.touchFxEnabled
        audioManager = getSystemService(Context.AUDIO_SERVICE) as android.media.AudioManager
        fun bindVol(seekId: Int, stream: Int) {
            val sb = findViewById<SeekBar>(seekId)
            sb.max = audioManager.getStreamMaxVolume(stream)
            sb.progress = audioManager.getStreamVolume(stream)
            sb.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                    if (fromUser) try { audioManager.setStreamVolume(stream, progress, 0) } catch (_: Exception) {}
                }
                override fun onStartTrackingTouch(seekBar: SeekBar?) { volumeHideRunnable?.let { root.removeCallbacks(it) } }
                override fun onStopTrackingTouch(seekBar: SeekBar?) { scheduleVolumeHide() }
            })
        }
        bindVol(R.id.volMedia, android.media.AudioManager.STREAM_MUSIC)
        bindVol(R.id.volRing, android.media.AudioManager.STREAM_RING)
        bindVol(R.id.volNotif, android.media.AudioManager.STREAM_NOTIFICATION)
    }

    private fun showVolumePanel() {
        try {
            if (!::volumePanel.isInitialized) return
            val streams = listOf(
                R.id.volMedia to android.media.AudioManager.STREAM_MUSIC,
                R.id.volRing to android.media.AudioManager.STREAM_RING,
                R.id.volNotif to android.media.AudioManager.STREAM_NOTIFICATION
            )
            streams.forEach { (id, stream) ->
                val sb = findViewById<SeekBar>(id)
                sb.max = audioManager.getStreamMaxVolume(stream)
                sb.progress = audioManager.getStreamVolume(stream)
            }
            volumePanel.animate().cancel()
            volumePanel.alpha = 0f
            volumePanel.translationX = 40f * resources.displayMetrics.density
            volumePanel.scaleX = 0.92f; volumePanel.scaleY = 0.92f
            volumePanel.isVisible = true
            volumePanel.animate().alpha(1f).translationX(0f).scaleX(1f).scaleY(1f).setDuration(180).setInterpolator(DecelerateInterpolator(1.8f)).start()
            scheduleVolumeHide()
        } catch (_: Exception) { }
    }

    private fun scheduleVolumeHide() {
        volumeHideRunnable?.let { root.removeCallbacks(it) }
        volumeHideRunnable = Runnable {
            if (::volumePanel.isInitialized) volumePanel.animate().alpha(0f).translationX(40f * resources.displayMetrics.density).scaleX(0.9f).scaleY(0.9f).setDuration(220).withEndAction {
                volumePanel.isVisible = false; volumePanel.translationX = 0f; volumePanel.scaleX = 1f; volumePanel.scaleY = 1f
            }.start()
        }
        root.postDelayed(volumeHideRunnable!!, 2000)
    }

    private fun setupSettings() {
        val allowFlagship = deviceSupportsFlagshipByDefault() || prefs.devMode
        flagshipSwitch.isVisible = allowFlagship
        if (!allowFlagship) prefs.flagshipAnimations = false
        flagshipSwitch.isChecked = prefs.flagshipAnimations
        flagshipSwitch.setOnCheckedChangeListener { _, c -> prefs.flagshipAnimations = c }
        blurSwitch.isChecked = prefs.blurEnabled
        blurSwitch.setOnCheckedChangeListener { _, c -> prefs.blurEnabled = c }
        findViewById<SwitchMaterial>(R.id.dockHideSwitch).apply { isChecked = prefs.dockHideOnOpen; setOnCheckedChangeListener { _, c -> prefs.dockHideOnOpen = c; if (c) animateDock(true) else { dockContainer.translationY = 0f; dockContainer.alpha = 1f; dockContainer.translationX = 0f; dockContainer.scaleX = 1f; dockContainer.scaleY = 1f } } }
        val dockHideStyleBtn = findViewById<Button>(R.id.dockHideStyleBtn)
        fun updDockHide() { dockHideStyleBtn.text = "Стиль скрытия дока: ${if (prefs.dockHideStyle == "genie") "Джин в угол" else "Слайд"}" }
        updDockHide()
        dockHideStyleBtn.setOnClickListener { val opts = arrayOf("Слайд","Джин в угол"); AlertDialog.Builder(this).setTitle("Скрытие дока").setItems(opts){_,w-> prefs.dockHideStyle = if (w==1) "genie" else "slide"; updDockHide() }.show() }
        val tiltSwitch = findViewById<SwitchMaterial>(R.id.tiltSwitch)
        tiltSwitch.isChecked = prefs.tiltAnimation
        tiltSwitch.setOnCheckedChangeListener { _, c -> prefs.tiltAnimation = c }
        bindSeek(findViewById(R.id.tiltIntensitySeek), prefs.tiltIntensity, 0, 100) { prefs.tiltIntensity = it }
        bindSeek(findViewById(R.id.windowCornerSeek), prefs.windowCornerRadius, 0, 60) { prefs.windowCornerRadius = it }
        val powerSwitch = findViewById<SwitchMaterial>(R.id.powerSavingSwitch)
        powerSwitch.isChecked = prefs.powerSaving
        powerSwitch.setOnCheckedChangeListener { _, c -> prefs.powerSaving = c; applyPowerSavingMode() }
        findViewById<Button>(R.id.powerBrightnessPermBtn).setOnClickListener { requestWriteSettingsPermission() }
        findViewById<Button>(R.id.powerBrightnessLowBtn).setOnClickListener { setLowBrightness() }
        findViewById<SwitchMaterial?>(R.id.unlockSoundSwitch)?.apply { isChecked = prefs.unlockSound; setOnCheckedChangeListener { _, c -> prefs.unlockSound = c } }
        bindSeek(findViewById(R.id.uiSoundVolumeSeek), prefs.uiSoundVolume, 0, 100) { prefs.uiSoundVolume = it }
        findViewById<SwitchMaterial?>(R.id.touchFxSwitch)?.apply { isChecked = prefs.touchFxEnabled; setOnCheckedChangeListener { _, c -> prefs.touchFxEnabled = c } }
        findViewById<Button?>(R.id.touchFxStyleBtn)?.setOnClickListener {
            val opts = arrayOf("Android","Фейерверк","Синяя вспышка MIUI","iOS 26 стекло")
            val vals = arrayOf("android","fireworks","miui","ios")
            AlertDialog.Builder(this).setTitle("Стиль касания").setItems(opts){_,w-> prefs.touchFxStyle = vals[w]; (it as? Button)?.text = "Стиль касания: ${opts[w]}" }.show()
        }
        findViewById<Button?>(R.id.touchFxEndBtn)?.setOnClickListener {
            val opts = arrayOf("Уменьшение","Расплывание","Распадание","Liquid Glass")
            val vals = arrayOf("shrink","fade","burst","liquid")
            AlertDialog.Builder(this).setTitle("Анимация исчезновения").setItems(opts){_,w-> prefs.touchFxEnd = vals[w]; (it as? Button)?.text = "Анимация исчезновения: ${opts[w]}" }.show()
        }
        bindSeek(findViewById(R.id.touchFxSizeSeek), prefs.touchFxSize, 10, 100) { prefs.touchFxSize = it }
        bindSeek(findViewById(R.id.touchFxSpeedSeek), prefs.touchFxSpeed, 10, 100) { prefs.touchFxSpeed = it }
        findViewById<Button?>(R.id.touchFxOverlayPermBtn)?.setOnClickListener {
            try { startActivity(Intent(android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION, android.net.Uri.parse("package:$packageName"))) } catch (_: Exception) {}
        }
        findViewById<SwitchMaterial?>(R.id.motionBlurSwitch)?.apply { isChecked = prefs.motionBlurBeta; setOnCheckedChangeListener { _, c -> prefs.motionBlurBeta = c } }
        findViewById<TextView?>(R.id.themePresetLabel)?.text = "Текущий: ${themePresetLabel(prefs.themePreset)}"
        findViewById<Button?>(R.id.themePresetBtn)?.setOnClickListener { showThemePresetPicker() }

        findViewById<Button?>(R.id.wallUnlockBlurModeBtn)?.setOnClickListener { prefs.wallUnlockBlurMode = if (prefs.wallUnlockBlurMode == "from") "to" else "from"; (it as? Button)?.text = "Режим: ${if (prefs.wallUnlockBlurMode=="from") "с размытия в нормальные" else "в размытие"}" }
        bindSeek(findViewById(R.id.wallUnlockBlurSeek), prefs.wallUnlockBlur, 0, 100) { prefs.wallUnlockBlur = it }
        findViewById<Button?>(R.id.wallZoomModeBtn)?.setOnClickListener { prefs.wallZoomMode = if (prefs.wallZoomMode == "in") "out" else "in"; (it as? Button)?.text = "Режим: ${if (prefs.wallZoomMode=="in") "приближение" else "отдаление"}" }
        bindSeek(findViewById(R.id.wallZoomSeek), prefs.wallZoom, 0, 100) { prefs.wallZoom = it }
        findViewById<Button?>(R.id.wallPickVideoBtn)?.setOnClickListener { try { wallVideoPickLauncher.launch(arrayOf("video/*")) } catch (_: Exception) {} }
        findViewById<Button?>(R.id.wallPickLockVideoBtn)?.setOnClickListener { try { wallLockVideoPickLauncher.launch(arrayOf("video/*")) } catch (_: Exception) {} }
        findViewById<TextView?>(R.id.wallVideoStatus)?.text = when {
            prefs.wallLockVideoUri.isNotBlank() && prefs.wallVideoUri.isNotBlank() -> "Видео обоев и разблокировки выбраны"
            prefs.wallVideoUri.isNotBlank() -> "Видео обоев выбрано"
            prefs.wallLockVideoUri.isNotBlank() -> "Видео разблокировки выбрано"
            else -> "Видео не выбрано"
        }

        findViewById<SwitchMaterial?>(R.id.islandEnabledSwitch)?.apply { isChecked = prefs.islandEnabled; setOnCheckedChangeListener { _, c -> prefs.islandEnabled = c; updateIslandVisibility() } }
        findViewById<SwitchMaterial?>(R.id.islandAlwaysSwitch)?.apply { isChecked = prefs.islandAlways; setOnCheckedChangeListener { _, c -> prefs.islandAlways = c; updateIslandVisibility() } }
        findViewById<SwitchMaterial?>(R.id.islandRightHandSwitch)?.apply { isChecked = prefs.islandRightHand; setOnCheckedChangeListener { _, c -> prefs.islandRightHand = c; updateIslandLayout() } }
        bindSeek(findViewById(R.id.islandXSeek), prefs.islandX, 0, 200) { prefs.islandX = it; updateIslandLayout() }
        bindSeek(findViewById(R.id.islandYSeek), prefs.islandY, 0, 200) { prefs.islandY = it; updateIslandLayout() }
        bindSeek(findViewById(R.id.islandWSeek), prefs.islandW, 20, 200) { prefs.islandW = it; updateIslandLayout() }
        bindSeek(findViewById(R.id.islandHSeek), prefs.islandH, 20, 200) { prefs.islandH = it; updateIslandLayout() }
        bindSeek(findViewById(R.id.islandPressSeek), prefs.islandPress, 0, 100) { prefs.islandPress = it }
        findViewById<Button?>(R.id.islandMusicPermBtn)?.setOnClickListener { try { startActivity(Intent("android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS")) } catch (_: Exception) {} }
        findViewById<Button?>(R.id.islandTestUnlockBtn)?.setOnClickListener { showIslandState("unlock") }
        findViewById<Button?>(R.id.islandTestMusicBtn)?.setOnClickListener { showIslandState("music") }
        findViewById<Button?>(R.id.islandTestFlashBtn)?.setOnClickListener { showIslandState("flash") }
        val labelsSwitch = findViewById<SwitchMaterial>(R.id.labelsSwitch)
        labelsSwitch.isChecked = prefs.showLabels
        labelsSwitch.setOnCheckedChangeListener { _, c -> prefs.showLabels = c; gridAdapter.notifyDataSetChanged() }
        findViewById<SwitchMaterial>(R.id.lightModeSwitch).apply { isChecked = prefs.lightMode; setOnCheckedChangeListener { _, c -> prefs.lightMode = c; applyVisualStyle(); applyAccent() } }
        findViewById<SwitchMaterial>(R.id.liquidGlassSwitch).apply { isChecked = prefs.liquidGlass; setOnCheckedChangeListener { _, c -> prefs.liquidGlass = c; applyVisualStyle() } }
        findViewById<SwitchMaterial>(R.id.liquidWobbleSwitch).apply { isChecked = prefs.liquidWobble; setOnCheckedChangeListener { _, c -> prefs.liquidWobble = c } }
        bindSeek(findViewById(R.id.liquidWobbleSeek), prefs.liquidWobbleIntensity, 0, 100) { prefs.liquidWobbleIntensity = it }
        val animPresetBtn = findViewById<Button>(R.id.animPresetBtn)
        fun updateAnimPresetLabel() { animPresetBtn.text = "Пресет анимации: ${if (prefs.animPreset == "genie") "Джин (macOS)" else "Пользовательская"}" }
        updateAnimPresetLabel()
        animPresetBtn.setOnClickListener {
            val opts = arrayOf("Пользовательская", "Джин (macOS)")
            AlertDialog.Builder(this).setTitle("Пресет анимации").setItems(opts) { _, w ->
                prefs.animPreset = if (w == 1) "genie" else "custom"; updateAnimPresetLabel()
            }.show()
        }
        var updDockStyle2: () -> Unit = {}
        val dockStyleBtn = findViewById<Button>(R.id.dockStyleBtn)
        fun updateDockStyleLabel() { dockStyleBtn.text = "Фон дока: ${when(prefs.dockBg){ "glass"->"Стекло"; "solid"->"Сплошной"; "transparent"->"Прозрачный"; else->"Размытие" }}" }
        updateDockStyleLabel()
        dockStyleBtn.setOnClickListener {
            val opts = listOf("blur" to "Размытие", "glass" to "Стекло", "solid" to "Сплошной", "transparent" to "Прозрачный")
            AlertDialog.Builder(this).setTitle("Фон дока").setItems(opts.map{it.second}.toTypedArray()) { _, w ->
                prefs.dockBg = opts[w].first
                updateDockStyleLabel(); updDockStyle2(); applyDockStyle(); updateDockPreview()
            }.show()
        }
        bindSeek(findViewById(R.id.iconScaleSeek), (prefs.iconScale - 60).coerceIn(0,80), 0, 80) { prefs.iconScale = it + 60; gridAdapter.notifyDataSetChanged(); drawerAdapter.notifyDataSetChanged(); renderDock() }
        bindSeek(findViewById(R.id.gridSideMarginSeek), prefs.gridSideMargin.coerceIn(0,64), 0, 64) { prefs.gridSideMargin = it; applyOrientationLayout() }

        bindSeek(findViewById(R.id.openDurationSeek), prefs.openDuration, 100, 700) { prefs.openDuration = it }
        bindSeek(findViewById(R.id.closeDurationSeek), prefs.closeDuration, 100, 700) { prefs.closeDuration = it }
        bindSeek(findViewById(R.id.openScaleSeek), (prefs.openScale * 100).toInt(), 20, 100) { prefs.openScale = it / 100f }
        bindSeek(findViewById(R.id.closeScaleSeek), (prefs.closeScale * 100).toInt(), 20, 100) { prefs.closeScale = it / 100f }
        bindSeek(findViewById(R.id.closeTySeek), prefs.closeArcHeight.toInt(), 0, 600) { prefs.closeArcHeight = it.toFloat() }
        bindSeek(findViewById(R.id.overshootSeek), (prefs.closeOvershoot * 100).toInt(), 0, 150) {
            prefs.closeOvershoot = it / 100f
        }

        findViewById<SwitchMaterial>(R.id.newCloseAnimSwitch).apply {
            isChecked = prefs.newCloseAnim
            setOnCheckedChangeListener { _, c -> prefs.newCloseAnim = c; updateAnimStudioVisibility() }
        }
        bindSeek(findViewById(R.id.closeStrengthSeek), prefs.closeStrength.coerceIn(0, 100), 0, 100) { prefs.closeStrength = it }
        findViewById<SwitchMaterial>(R.id.closeRandomSwitch).apply {
            isChecked = prefs.closeRandomStrength
            setOnCheckedChangeListener { _, c -> prefs.closeRandomStrength = c }
        }
        bindSeek(findViewById(R.id.closeIconScaleSeek), (100 - prefs.closeIconScale.coerceIn(50, 100)), 0, 50) {
            prefs.closeIconScale = 100 - it
        }
        fun updZoomLabels() {
            val om = when (prefs.openZoomMode) { "in" -> "приближение"; "out" -> "отдаление"; else -> "без зума" }
            val cm = when (prefs.closeZoomMode) { "in" -> "приближение"; "out" -> "отдаление"; else -> "без зума" }
            findViewById<Button?>(R.id.openZoomModeBtn)?.text = "Открытие: $om"
            findViewById<Button?>(R.id.closeZoomModeBtn)?.text = "Закрытие: $cm"
        }
        updZoomLabels()
        findViewById<Button?>(R.id.openZoomModeBtn)?.setOnClickListener {
            val modes = arrayOf("none","in","out"); val labels = arrayOf("Без зума","Приближение","Отдаление")
            AlertDialog.Builder(this).setTitle("Зум при открытии").setItems(labels) { _, w -> prefs.openZoomMode = modes[w]; updZoomLabels() }.show()
        }
        findViewById<Button?>(R.id.closeZoomModeBtn)?.setOnClickListener {
            val modes = arrayOf("none","in","out"); val labels = arrayOf("Без зума","Приближение","Отдаление")
            AlertDialog.Builder(this).setTitle("Зум при закрытии").setItems(labels) { _, w -> prefs.closeZoomMode = modes[w]; updZoomLabels() }.show()
        }
        bindSeek(findViewById(R.id.openZoomAmountSeek), prefs.openZoomAmount, 0, 100) { prefs.openZoomAmount = it }
        bindSeek(findViewById(R.id.openZoomSpeedSeek), (prefs.openZoomSpeed - 20).coerceIn(0,180), 0, 180) { prefs.openZoomSpeed = it + 20 }
        bindSeek(findViewById(R.id.closeZoomAmountSeek), prefs.closeZoomAmount, 0, 100) { prefs.closeZoomAmount = it }
        setupAnimStudio()

        updateIconPackLabel()
        findViewById<Button>(R.id.pickIconPackBtn).setOnClickListener { showIconPackPicker() }
        findViewById<Button>(R.id.wallpaperBtn).setOnClickListener { openWallpaperPicker() }
        val accentMap = mapOf(
            R.id.accentPurple to 0xFF7C4DFF.toInt(), R.id.accentBlue to 0xFF448AFF.toInt(),
            R.id.accentGreen to 0xFF00C853.toInt(), R.id.accentOrange to 0xFFFF6D00.toInt(),
            R.id.accentPink to 0xFFFF4081.toInt(), R.id.accentRed to 0xFFFF5252.toInt()
        )
        accentMap.forEach { (id, color) ->
            findViewById<View>(id).setOnClickListener {
                prefs.accentColor = color
                applyAccent()
            }
        }
        applyAccent()
        updateIconShapeLabel()
        findViewById<Button>(R.id.iconShapeBtn).setOnClickListener { showIconShapePicker() }
        setupCornerSeekbars()
        updateCornerPreview()
        updateUnlockLabel()
        findViewById<Button>(R.id.unlockAnimBtn).setOnClickListener { showUnlockAnimPicker() }
        findViewById<SeekBar?>(R.id.iosOldTopScaleSeek)?.let { bindSeek(it, prefs.iosOldTopScale, 120, 400) { v -> prefs.iosOldTopScale = v; updateUnlockLabel() } }
        findViewById<SeekBar?>(R.id.iosOldBottomScaleSeek)?.let { bindSeek(it, prefs.iosOldBottomScale, 100, 180) { v -> prefs.iosOldBottomScale = v; updateUnlockLabel() } }
        findViewById<SeekBar?>(R.id.iosOldSpeedSeek)?.let { bindSeek(it, prefs.iosOldSpeed, 40, 220) { v -> prefs.iosOldSpeed = v; updateUnlockLabel() } }
        updateDrawerStyleLabel()
        findViewById<Button>(R.id.drawerStyleBtn).setOnClickListener { showDrawerStylePicker() }
        updateDrawerLayoutLabel()
        findViewById<Button>(R.id.drawerLayoutBtn).setOnClickListener { showDrawerLayoutPicker() }
        bindSeek(findViewById(R.id.drawerBlurSeek), prefs.drawerBlur, 0, 100) { prefs.drawerBlur = it }
        bindSeek(findViewById(R.id.drawerDimSeek), prefs.drawerDim, 0, 100) { prefs.drawerDim = it }
        bindSeek(findViewById(R.id.drawerGridColsSeek), (prefs.drawerGridCols - 3).coerceIn(0,3), 0, 3) { prefs.drawerGridCols = it + 3; applyDrawerLayout() }
        bindSeek(findViewById(R.id.drawerFolderRadiusSeek), prefs.drawerFolderRadius, 0, 40) { prefs.drawerFolderRadius = it }
        findViewById<Button?>(R.id.drawerMoveFoldersBtn)?.setOnClickListener { settingsOverlay.isVisible = false; openDrawer(); android.widget.Toast.makeText(this, "Режим перемещения: зажмите папку/иконку в меню", android.widget.Toast.LENGTH_SHORT).show() }
        findViewById<SwitchMaterial>(R.id.desktopModeSwitch).apply { isChecked = prefs.desktopMode; setOnCheckedChangeListener { _, c -> prefs.desktopMode = c; updateLandDockLabel(); applyOrientationLayout(); renderDock(); renderCurrentPage() } }
        updateLandDockLabel()
        findViewById<Button>(R.id.landDockBtn).setOnClickListener { showLandDockPicker() }
        // dock scale moved to Dock category
        bindSeek(findViewById(R.id.gridRowsSeek), prefs.homeGridRows - 4, 0, 4) { prefs.homeGridRows = it + 4; applyOrientationLayout(); renderCurrentPage(); persistLayout() }
        bindSeek(findViewById(R.id.portColsSeek), prefs.portraitGridCols - 3, 0, 3) { prefs.portraitGridCols = it + 3; applyOrientationLayout(); renderCurrentPage() }
        bindSeek(findViewById(R.id.portPadSeek), prefs.portraitGridPadding, 0, 40) { prefs.portraitGridPadding = it; applyOrientationLayout() }
        bindSeek(findViewById(R.id.landColsSeek), prefs.landscapeGridCols - 4, 0, 4) { prefs.landscapeGridCols = it + 4; applyOrientationLayout(); renderCurrentPage() }
        bindSeek(findViewById(R.id.landPadSeek), prefs.landscapeGridPadding, 0, 40) { prefs.landscapeGridPadding = it; applyOrientationLayout() }
        bindSeek(findViewById(R.id.landScaleSeek), prefs.landscapeIconScale - 60, 0, 60) { prefs.landscapeIconScale = it + 60; applyOrientationLayout() }
        bindSeek(findViewById(R.id.gridTopPadSeek), prefs.gridTopPadding, 0, 200) { prefs.gridTopPadding = it; applyOrientationLayout() }
        bindSeek(findViewById(R.id.gridBottomPadSeek), prefs.gridBottomPadding, 0, 200) { prefs.gridBottomPadding = it; applyOrientationLayout() }
        val screenScaleLabel = findViewById<TextView>(R.id.screenScaleLabel)
        fun updScreenScale() { screenScaleLabel.text = "${prefs.screenScale}%" }
        updScreenScale()
        bindSeek(findViewById(R.id.screenScaleSeek), (prefs.screenScale - 50).coerceIn(0,150), 0, 150) { prefs.screenScale = it + 50; updScreenScale(); applyOrientationLayout() }
        val rrBtn = findViewById<Button>(R.id.refreshRateBtn)
        fun updRr() { rrBtn.text = "Гц: ${if (prefs.refreshRatePref==0) "Авто" else prefs.refreshRatePref.toString()}" }
        updRr()
        rrBtn.setOnClickListener { showRefreshRatePicker(updRr = { updRr() }) }
        findViewById<Button?>(R.id.aspectRatioBtn)?.setOnClickListener { showAspectRatioEditor() }

        findViewById<SwitchMaterial>(R.id.fullscreenSwitch).apply {
            isChecked = prefs.fullscreenMode
            setOnCheckedChangeListener { _, c -> prefs.fullscreenMode = c; applyFullscreenMode() }
        }
        findViewById<SwitchMaterial>(R.id.doubleTapLockSwitch).apply {
            isChecked = prefs.doubleTapLock
            setOnCheckedChangeListener { _, c -> prefs.doubleTapLock = c }
        }
        val lockAnimLabel = findViewById<TextView>(R.id.lockAnimLabel)
        fun updLockAnim() { lockAnimLabel.text = "Анимация: ${lockAnimDisplayName(prefs.lockAnimation)}" }
        updLockAnim()
        findViewById<Button>(R.id.lockAnimBtn).setOnClickListener { showLockAnimPicker { updLockAnim() } }
        findViewById<Button?>(R.id.lockAnimBtnAnim)?.setOnClickListener { showLockAnimPicker { updLockAnim() } }
        findViewById<SwitchMaterial>(R.id.aodSwitch).apply {
            isChecked = prefs.aodEnabled
            setOnCheckedChangeListener { _, c ->
                prefs.aodEnabled = c
                if (c) ensureAodPermissionAndStart() else stopAodOverlay()
            }
        }
        findViewById<Button>(R.id.aodSettingsBtn).setOnClickListener { showAodSettingsDialog() }
        bindSeek(findViewById(R.id.screenInsetTopSeek), prefs.screenInsetTop, 0, 200) { prefs.screenInsetTop = it; applyScreenInsets() }
        bindSeek(findViewById(R.id.screenInsetBottomSeek), prefs.screenInsetBottom, 0, 200) { prefs.screenInsetBottom = it; applyScreenInsets() }
        bindSeek(findViewById(R.id.screenInsetLeftSeek), prefs.screenInsetLeft, 0, 200) { prefs.screenInsetLeft = it; applyScreenInsets() }
        bindSeek(findViewById(R.id.screenInsetRightSeek), prefs.screenInsetRight, 0, 200) { prefs.screenInsetRight = it; applyScreenInsets() }
        findViewById<SwitchMaterial?>(R.id.virtualStatusSwitch)?.apply { isChecked = prefs.virtualStatusBar; setOnCheckedChangeListener { _, c -> prefs.virtualStatusBar = c; updateVirtualStatusBar() } }
        findViewById<SwitchMaterial?>(R.id.vsbClockCenterSwitch)?.apply { isChecked = prefs.virtualStatusClockCenter; setOnCheckedChangeListener { _, c -> prefs.virtualStatusClockCenter = c; updateVirtualStatusBar() } }
        findViewById<SwitchMaterial?>(R.id.vsbBatteryPctSwitch)?.apply { isChecked = prefs.virtualStatusBatteryPercent; setOnCheckedChangeListener { _, c -> prefs.virtualStatusBatteryPercent = c; updateVirtualStatusBar() } }
        bindSeek(findViewById(R.id.vsbHeightSeek), (prefs.virtualStatusHeight - 20).coerceIn(0,40), 0, 40) { prefs.virtualStatusHeight = it + 20; updateVirtualStatusBar(); applyScreenInsets() }
        bindSeek(findViewById(R.id.vsbOffsetXSeek), (prefs.virtualStatusOffsetX + 200).coerceIn(0,400), 0, 400) { prefs.virtualStatusOffsetX = it - 200; updateVirtualStatusBar() }
        bindSeek(findViewById(R.id.vsbOffsetYSeek), (prefs.virtualStatusOffsetY + 200).coerceIn(0,400), 0, 400) { prefs.virtualStatusOffsetY = it - 200; updateVirtualStatusBar(); applyScreenInsets() }

        // Dock category
        val dockStyleLabel2 = findViewById<TextView>(R.id.dockStyleLabel)
        val dockBgLabel = findViewById<TextView>(R.id.dockBgLabel)
        val dockScaleSeek2 = findViewById<SeekBar>(R.id.dockScaleSeek2)
        val dockWidthSeek = findViewById<SeekBar?>(R.id.dockWidthSeek)
        val dockHeightSeek = findViewById<SeekBar?>(R.id.dockHeightSeek)
        val dockRadiusSeek = findViewById<SeekBar>(R.id.dockRadiusSeek)
        val dockOffsetXSeek = findViewById<SeekBar>(R.id.dockOffsetXSeek)
        val dockOffsetYSeek = findViewById<SeekBar>(R.id.dockOffsetYSeek)
        val dockTransparencySeek = findViewById<SeekBar>(R.id.dockTransparencySeek)
        val dockHideSwitch2 = findViewById<SwitchMaterial>(R.id.dockHideSwitch2)
        val dockHideStyleBtn2 = findViewById<Button>(R.id.dockHideStyleBtn2)
        val dockBounceSeek = findViewById<SeekBar>(R.id.dockBounceSeek)
        val dockBounceLabel = findViewById<TextView>(R.id.dockBounceLabel)
        fun refreshDockSeekbars() {
            dockScaleSeek2.progress = (prefs.dockScale - 70).coerceIn(0,70)
            dockWidthSeek?.progress = (prefs.dockWidth - 40).coerceIn(0,60)
            dockHeightSeek?.progress = (prefs.dockHeight - 56).coerceIn(0,84)
            dockRadiusSeek.progress = prefs.dockRadius.coerceIn(0,50)
            dockOffsetXSeek.progress = (prefs.dockXOffset + 200).coerceIn(0,400)
            dockOffsetYSeek.progress = (prefs.dockYOffset + 200).coerceIn(0,400)
            dockTransparencySeek.progress = prefs.dockTransparency.coerceIn(0,100)
            dockHideSwitch2.isChecked = prefs.dockHideOnOpen
            dockBounceSeek.progress = prefs.dockBounceMs.coerceIn(0,2000)
            dockBounceLabel.text = if (prefs.dockBounceMs >= 1000) String.format("%.2f с", prefs.dockBounceMs / 1000f) else "${prefs.dockBounceMs} мс"
            dockHideStyleBtn2.text = "Стиль скрытия: ${if (prefs.dockHideStyle == "genie") "Джинн" else "Слайд"}"
        }
        updDockStyle2 = {
            val ln = when (prefs.dockLayout) { "harbor" -> "Harbor"; "orbit" -> "Orbit"; else -> "Standard" }
            val bn = when (prefs.dockBg) { "glass" -> "Стекло"; "solid" -> "Сплошной"; "transparent" -> "Прозрачный"; else -> "Размытие" }
            dockStyleLabel2.text = "Макет: $ln"
            dockBgLabel.text = "Фон: $bn"
            refreshDockSeekbars()
            updateDockPreview()
        }
        updDockStyle2()
        findViewById<Button>(R.id.dockLayoutBtn).setOnClickListener {
            val opts = listOf("standard" to "Standard (классический)", "harbor" to "Harbor (компактный, как macOS)", "orbit" to "Orbit (боковой, как iPhone Fold)")
            AlertDialog.Builder(this).setTitle("Макет дока").setItems(opts.map{it.second}.toTypedArray()) { _, w ->
                prefs.dockLayout = opts[w].first
                if (prefs.dockLayout == "harbor" && prefs.dockCornerMode == "bottom") prefs.dockCornerMode = "bottom-left"
                updDockStyle2(); applyDockStyle(); applyOrientationLayout(); renderDock()
            }.show()
        }
        findViewById<Button>(R.id.dockBgBtn).setOnClickListener {
            val opts = listOf("blur" to "Размытие", "glass" to "Стекло", "solid" to "Сплошной", "transparent" to "Прозрачный")
            AlertDialog.Builder(this).setTitle("Фон дока").setItems(opts.map{it.second}.toTypedArray()) { _, w ->
                prefs.dockBg = opts[w].first; updateDockStyleLabel(); updDockStyle2(); applyDockStyle(); updateDockPreview()
            }.show()
        }
        bindSeek(dockScaleSeek2, (prefs.dockScale - 70).coerceIn(0,70), 0, 70) { prefs.dockScale = it + 70; applyOrientationLayout(); renderDock(); updateDockPreview() }
        dockWidthSeek?.let { bindSeek(it, (prefs.dockWidth - 40).coerceIn(0,60), 0, 60) { v -> prefs.dockWidth = v + 40; applyOrientationLayout(); renderDock(); updateDockPreview() } }
        dockHeightSeek?.let { bindSeek(it, (prefs.dockHeight - 56).coerceIn(0,84), 0, 84) { v -> prefs.dockHeight = v + 56; applyOrientationLayout(); renderDock(); updateDockPreview() } }
        bindSeek(dockRadiusSeek, prefs.dockRadius.coerceIn(0,50), 0, 50) { prefs.dockRadius = it; applyDockStyle(); updateDockPreview() }
        bindSeek(dockTransparencySeek, prefs.dockTransparency.coerceIn(0,100), 0, 100) { prefs.dockTransparency = it; applyDockStyle(); updateDockPreview() }
        bindSeek(dockOffsetXSeek, (prefs.dockXOffset + 200).coerceIn(0,400), 0, 400) { prefs.dockXOffset = it - 200; applyOrientationLayout(); renderDock(); updateDockPreview() }
        bindSeek(dockOffsetYSeek, (prefs.dockYOffset + 200).coerceIn(0,400), 0, 400) { prefs.dockYOffset = it - 200; applyOrientationLayout(); renderDock(); updateDockPreview() }
        dockHideSwitch2.setOnCheckedChangeListener { _, c -> prefs.dockHideOnOpen = c }
        dockHideStyleBtn2.setOnClickListener {
            val opts = arrayOf("Слайд","Джинн (всасывание)")
            AlertDialog.Builder(this).setTitle("Стиль скрытия дока").setItems(opts){_,w-> prefs.dockHideStyle = if (w==1) "genie" else "slide"; refreshDockSeekbars() }.show()
        }
        bindSeek(dockBounceSeek, prefs.dockBounceMs.coerceIn(0,2000), 0, 2000) { prefs.dockBounceMs = it; dockBounceLabel.text = if (it >= 1000) String.format("%.2f с", it / 1000f) else "$it мс" }

        settingsButton.setOnClickListener {
            showSettingsMain()
            settingsOverlay.isVisible = true
            quickPanel.isVisible = false; settingsButton.isVisible = false
        }
        setupSettingsNavigation()
        findViewById<Button>(R.id.uninstallLauncherBtn).setOnClickListener {
            try { startActivity(Intent(Intent.ACTION_DELETE, android.net.Uri.parse("package:$packageName"))) }
            catch (_: Exception) { try { startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, android.net.Uri.parse("package:$packageName"))) } catch (_: Exception) {} }
        }
        findViewById<View>(R.id.quickWallpaper).setOnClickListener { quickPanel.isVisible = false; openWallpaperPicker() }
        findViewById<View>(R.id.quickSettings).setOnClickListener { quickPanel.isVisible = false; showSettingsMain(); settingsOverlay.isVisible = true }
        findViewById<View>(R.id.quickIcons).setOnClickListener { quickPanel.isVisible = false; showIconShapePicker() }
        findViewById<View>(R.id.quickWidgets).setOnClickListener { quickPanel.isVisible = false; requestAddWidget() }
        settingsOverlay.setOnClickListener { settingsOverlay.isVisible = false }
        findViewById<View>(R.id.settingsMain).setOnClickListener { }
        findViewById<View>(R.id.settingsDetail).setOnClickListener { }
    }

    private fun bindSeek(seek: SeekBar, value: Int, min: Int, max: Int, onChange: (Int) -> Unit) {
        seek.max = max - min
        seek.progress = (value - min).coerceIn(0, max - min)
        seek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) onChange(progress + min)
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })
    }

    // ---- Lock / fullscreen / AOD ----

    private fun lockAnimDisplayName(key: String): String = when (key) {
        "pixel" -> "Pixel (в кнопку питания)"
        "xiaomi" -> "Xiaomi / Poco"
        "oneplus" -> "OnePlus"
        "oukitel" -> "Oukitel"
        "samsung" -> "Samsung (снизу вверх)"
        "wave" -> "Волна"
        "fire" -> "Огонь"
        "ice" -> "Лёд"
        "nothing" -> "Nothing (точки)"
        "miui13" -> "MIUI 13"
        "hyperos" -> "HyperOS"
        "iphone" -> "iPhone"
        "iphone8" -> "iPhone 8 (плавное затемнение)"
        "pixel2" -> "Pixel (градиент сверху/снизу)"
        else -> "Pixel"
    }

    private fun showLockAnimPicker(onChanged: () -> Unit = {}) {
        val opts = listOf("pixel","xiaomi","oneplus","oukitel","samsung","wave","fire","ice","nothing","miui13","hyperos","iphone","iphone8","pixel2")
        val names = opts.map { lockAnimDisplayName(it) }.toTypedArray()
        AlertDialog.Builder(this).setTitle("Анимация блокировки").setItems(names) { _, w ->
            prefs.lockAnimation = opts[w]
            findViewById<TextView?>(R.id.lockAnimLabel)?.text = "Анимация: ${lockAnimDisplayName(prefs.lockAnimation)}"
            onChanged()
        }.show()
    }

    private fun applyFullscreenMode() {
        try {
            androidx.core.view.WindowCompat.setDecorFitsSystemWindows(window, !prefs.fullscreenMode)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val c = window.insetsController
                if (prefs.fullscreenMode) {
                    c?.hide(android.view.WindowInsets.Type.statusBars() or android.view.WindowInsets.Type.navigationBars())
                    c?.systemBarsBehavior = android.view.WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                } else {
                    c?.show(android.view.WindowInsets.Type.statusBars() or android.view.WindowInsets.Type.navigationBars())
                }
            } else {
                @Suppress("DEPRECATION")
                window.decorView.systemUiVisibility = if (prefs.fullscreenMode)
                    (View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION)
                else (View.SYSTEM_UI_FLAG_LAYOUT_STABLE)
            }
            window.statusBarColor = Color.TRANSPARENT
            window.navigationBarColor = Color.TRANSPARENT
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                window.isNavigationBarContrastEnforced = false
                window.isStatusBarContrastEnforced = false
            }
            findViewById<View?>(R.id.root)?.fitsSystemWindows = !prefs.fullscreenMode
        } catch (_: Exception) {}
    }

    private fun applyScreenInsets() {
        try {
            val d = resources.displayMetrics.density
            val l = (prefs.screenInsetLeft * d).toInt()
            val tBase = (prefs.screenInsetTop * d).toInt()
            val r = (prefs.screenInsetRight * d).toInt()
            val b = (prefs.screenInsetBottom * d).toInt()
            fun bar(v: View?, size: Int, horizontal: Boolean) {
                if (v == null) return
                val lp = v.layoutParams
                if (horizontal) { lp.height = size; lp.width = FrameLayout.LayoutParams.MATCH_PARENT } else { lp.width = size; lp.height = FrameLayout.LayoutParams.MATCH_PARENT }
                v.layoutParams = lp
                v.isVisible = size > 0
            }
            bar(findViewById(R.id.insetBarTop), tBase, true)
            bar(findViewById(R.id.insetBarBottom), b, true)
            bar(findViewById(R.id.insetBarLeft), l, false)
            bar(findViewById(R.id.insetBarRight), r, false)
            updateVirtualStatusBar()
            applyOrientationLayout()
        } catch (_: Exception) {}
    }

    private fun showAspectRatioEditor() {
        val d = resources.displayMetrics.density
        val scrim = FrameLayout(this).apply { setBackgroundColor(0xCC000000.toInt()); isClickable = true; isFocusable = true }
        val frame = View(this).apply { background = android.graphics.drawable.GradientDrawable().apply { setStroke((4*d).toInt(), 0xFF448AFF.toInt()); setColor(0x00000000) } }
        val controls = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding((16*d).toInt(), (16*d).toInt(), (16*d).toInt(), (16*d).toInt()); background = android.graphics.drawable.GradientDrawable().apply { cornerRadius = 16*d; setColor(0xEE222222.toInt()) } }
        fun label(t: String) = TextView(this).apply { text = t; setTextColor(Color.WHITE); textSize = 13f }
        val lS = SeekBar(this).apply { max = 200; progress = prefs.screenInsetLeft }
        val rS = SeekBar(this).apply { max = 200; progress = prefs.screenInsetRight }
        val tS = SeekBar(this).apply { max = 200; progress = prefs.screenInsetTop }
        val bS = SeekBar(this).apply { max = 200; progress = prefs.screenInsetBottom }
        fun updateFrame() {
            val lp = frame.layoutParams as FrameLayout.LayoutParams
            lp.leftMargin = (lS.progress*d).toInt(); lp.rightMargin = (rS.progress*d).toInt()
            lp.topMargin = (tS.progress*d).toInt(); lp.bottomMargin = (bS.progress*d).toInt()
            frame.layoutParams = lp
            prefs.screenInsetLeft = lS.progress; prefs.screenInsetRight = rS.progress
            prefs.screenInsetTop = tS.progress; prefs.screenInsetBottom = bS.progress
            applyScreenInsets()
        }
        val listener = object: SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) { if (fromUser) updateFrame() }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        }
        listOf(lS,rS,tS,bS).forEach { it.setOnSeekBarChangeListener(listener) }
        controls.addView(label("Слева")); controls.addView(lS)
        controls.addView(label("Справа")); controls.addView(rS)
        controls.addView(label("Сверху")); controls.addView(tS)
        controls.addView(label("Снизу")); controls.addView(bS)
        val close = Button(this).apply { text = "Готово"; setOnClickListener { try { root.removeView(scrim) } catch (_: Exception) {} } }
        val reset = Button(this).apply { text = "Сброс"; setOnClickListener { lS.progress=0; rS.progress=0; tS.progress=0; bS.progress=0; updateFrame() } }
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        row.addView(reset, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        row.addView(close, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        controls.addView(row)
        val frameLp = FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
        frameLp.leftMargin = (prefs.screenInsetLeft*d).toInt(); frameLp.rightMargin = (prefs.screenInsetRight*d).toInt()
        frameLp.topMargin = (prefs.screenInsetTop*d).toInt(); frameLp.bottomMargin = (prefs.screenInsetBottom*d).toInt()
        scrim.addView(frame, frameLp)
        val ctrlLp = FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT)
        ctrlLp.gravity = android.view.Gravity.BOTTOM; ctrlLp.marginStart = (12*d).toInt(); ctrlLp.marginEnd = (12*d).toInt(); ctrlLp.bottomMargin = (24*d).toInt()
        scrim.addView(controls, ctrlLp)
        root.addView(scrim, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
    }

    private fun updateVirtualStatusBar() {
        try {
            val bar = findViewById<View?>(R.id.virtualStatusBar) ?: return
            bar.isVisible = prefs.virtualStatusBar
            val d = resources.displayMetrics.density
            val lp = bar.layoutParams as FrameLayout.LayoutParams
            lp.height = (prefs.virtualStatusHeight.coerceIn(20,60) * d).toInt()
            lp.topMargin = (prefs.screenInsetTop * d).toInt() + (prefs.virtualStatusOffsetY * d).toInt()
            lp.marginStart = (prefs.screenInsetLeft * d).toInt() + (prefs.virtualStatusOffsetX * d).toInt()
            lp.marginEnd = (prefs.screenInsetRight * d).toInt() - (prefs.virtualStatusOffsetX * d).toInt()
            bar.layoutParams = lp
            val clock = java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault()).format(java.util.Date())
            val left = findViewById<TextView?>(R.id.vsbLeft)
            val center = findViewById<TextView?>(R.id.vsbCenter)
            val right = findViewById<TextView?>(R.id.vsbRight)
            if (prefs.virtualStatusClockCenter) { left?.text = ""; center?.text = clock } else { left?.text = clock; center?.text = "" }
            val bm = getSystemService(Context.BATTERY_SERVICE) as? android.os.BatteryManager
            val pct = bm?.getIntProperty(android.os.BatteryManager.BATTERY_PROPERTY_CAPACITY) ?: 0
            right?.text = "Wi-Fi  ${if (prefs.virtualStatusBatteryPercent) "$pct%" else ""}"
        } catch (_: Exception) {}
    }

    private fun performDoubleTapLock() {
        if (!prefs.doubleTapLock) return
        val accOk = LockAccessibilityService.isEnabled()
        val dpm = getSystemService(Context.DEVICE_POLICY_SERVICE) as android.app.admin.DevicePolicyManager
        val admin = ComponentName(this, LockAdminReceiver::class.java)
        val adminOk = dpm.isAdminActive(admin)
        if (!accOk && !adminOk) {
            AlertDialog.Builder(this)
                .setTitle("Нужно разрешение")
                .setMessage("Чтобы блокировать экран двойным тапом, включи специальную возможность Vibe Launcher или выдай права администратора устройства.")
                .setPositiveButton("Спец. возможности") { _, _ ->
                    try { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) } catch (_: Exception) {}
                }
                .setNeutralButton("Администратор") { _, _ ->
                    try {
                        val i = Intent(android.app.admin.DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN)
                        i.putExtra(android.app.admin.DevicePolicyManager.EXTRA_DEVICE_ADMIN, admin)
                        i.putExtra(android.app.admin.DevicePolicyManager.EXTRA_ADD_EXPLANATION, "Нужно для блокировки экрана двойным тапом")
                        startActivity(i)
                    } catch (_: Exception) {}
                }
                .setNegativeButton("Отмена", null)
                .show()
            return
        }
        playLockAnimationThenLock()
    }

    private fun playLockAnimationThenLock() {
        try {
            val w = root.width.coerceAtLeast(1).toFloat()
            val h = root.height.coerceAtLeast(1).toFloat()
            window.addFlags(android.view.WindowManager.LayoutParams.FLAG_FULLSCREEN)
            val anim = prefs.lockAnimation
            val extras = mutableListOf<View>()
            fun addOverlay(color: Int = Color.BLACK, alpha: Float = 0f): View {
                val v = View(this).apply { setBackgroundColor(color); this.alpha = alpha; isClickable = true; isFocusable = true }
                root.addView(v, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
                extras.add(v); return v
            }
            val overlay = addOverlay(Color.BLACK, 0f)
            val bs = (prefs.screenScale.coerceIn(50,200) / 100f)
            val cleanup = Runnable {
                val locked = LockAccessibilityService.lock()
                if (!locked) try {
                    val dpm = getSystemService(Context.DEVICE_POLICY_SERVICE) as android.app.admin.DevicePolicyManager
                    if (dpm.isAdminActive(ComponentName(this, LockAdminReceiver::class.java))) dpm.lockNow()
                } catch (_: Exception) {}
                overlay.postDelayed({
                    extras.forEach { v -> try { root.removeView(v) } catch (_: Exception) {} }
                    if (!prefs.fullscreenMode) window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_FULLSCREEN)
                    homeContent.animate().cancel()
                    homeContent.alpha = 1f; homeContent.translationX = 0f; homeContent.translationY = 0f
                    homeContent.scaleX = bs; homeContent.scaleY = bs; homeContent.rotation = 0f
                }, 450)
            }

            when (anim) {
                "pixel" -> { // shrink into power button (top-right) with darkening
                    homeContent.pivotX = w; homeContent.pivotY = 0f
                    homeContent.animate().scaleX(bs * 0.15f).scaleY(bs * 0.15f).alpha(0f).setDuration(420)
                        .setInterpolator(DecelerateInterpolator(2f)).start()
                    overlay.animate().alpha(1f).setDuration(380).setStartDelay(60).withEndAction(cleanup).start()
                }
                "xiaomi" -> { // smooth calm fade, no motion
                    overlay.animate().alpha(1f).setDuration(520).setInterpolator(DecelerateInterpolator(1.2f)).withEndAction(cleanup).start()
                }
                "oneplus" -> { // quick scale down + white flash + black
                    val flash = addOverlay(Color.WHITE, 0f)
                    homeContent.animate().scaleX(bs * 0.88f).scaleY(bs * 0.88f).setDuration(180).start()
                    flash.animate().alpha(0.35f).setDuration(120).withEndAction {
                        flash.animate().alpha(0f).setDuration(180).start()
                        overlay.animate().alpha(1f).setDuration(260).withEndAction(cleanup).start()
                    }.start()
                }
                "oukitel" -> { // dim, wait 0.5s, off
                    overlay.animate().alpha(0.35f).setDuration(180).withEndAction {
                        overlay.postDelayed({
                            overlay.animate().alpha(1f).setDuration(220).withEndAction(cleanup).start()
                        }, 500)
                    }.start()
                }
                "samsung" -> { // bottom-to-top curtain
                    val curtain = addOverlay(Color.BLACK, 1f)
                    curtain.pivotY = h; curtain.scaleY = 0f
                    curtain.animate().scaleY(1f).setDuration(480).setInterpolator(DecelerateInterpolator(1.5f)).withEndAction(cleanup).start()
                    overlay.alpha = 0f
                }
                "wave" -> { // dark band sweeps top to bottom
                    val band = addOverlay(Color.BLACK, 1f)
                    band.translationY = -h
                    band.animate().translationY(0f).setDuration(520).setInterpolator(DecelerateInterpolator(1.3f)).withEndAction(cleanup).start()
                    homeContent.animate().translationY(bs * 12f).setDuration(200).withEndAction {
                        homeContent.animate().translationY(0f).setDuration(280).start()
                    }.start()
                    overlay.alpha = 0f
                }
                "fire" -> { // orange-red sweep then black
                    val fire = addOverlay(0xFFFF3D00.toInt(), 1f)
                    fire.translationY = -h
                    fire.animate().translationY(0f).setDuration(420).setInterpolator(DecelerateInterpolator(1.4f)).withEndAction {
                        overlay.alpha = 1f
                        fire.animate().alpha(0f).setDuration(200).withEndAction(cleanup).start()
                    }.start()
                    overlay.alpha = 0f
                }
                "ice" -> { // frost white-blue then freeze to black
                    val frost = addOverlay(0xFFE0F7FA.toInt(), 0f)
                    frost.animate().alpha(0.75f).setDuration(260).withEndAction {
                        overlay.alpha = 0.6f
                        overlay.animate().alpha(1f).setDuration(320).start()
                        frost.animate().alpha(0f).setDuration(320).withEndAction(cleanup).start()
                    }.start()
                }
                "nothing" -> { // dot grid appears
                    overlay.setBackgroundColor(0xFF000000.toInt())
                    overlay.animate().alpha(0.55f).setDuration(200).start()
                    val dots = FrameLayout(this)
                    root.addView(dots, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
                    extras.add(dots)
                    val dotSize = (4 * resources.displayMetrics.density).toInt()
                    val step = (28 * resources.displayMetrics.density).toInt()
                    val cols = (w / step).toInt() + 1
                    val rows = (h / step).toInt() + 1
                    for (r in 0..rows) for (c in 0..cols) {
                        if ((r + c) % 3 != 0) continue
                        val dot = View(this)
                        val gd = android.graphics.drawable.GradientDrawable()
                        gd.shape = android.graphics.drawable.GradientDrawable.OVAL
                        gd.setColor(0xFFFFFFFF.toInt())
                        dot.background = gd; dot.alpha = 0f
                        val lp = FrameLayout.LayoutParams(dotSize, dotSize)
                        lp.leftMargin = c * step; lp.topMargin = r * step
                        dots.addView(dot, lp)
                        dot.animate().alpha(0.9f).setStartDelay((r * 18 + c * 8).toLong()).setDuration(180).start()
                    }
                    overlay.postDelayed({ overlay.animate().alpha(1f).setDuration(220).withEndAction(cleanup).start() }, 520)
                }
                "miui13" -> { // smooth slow dim, MIUI-like curve
                    homeContent.animate().scaleX(bs * 0.97f).scaleY(bs * 0.97f).setDuration(380).setInterpolator(DecelerateInterpolator(1.5f)).start()
                    overlay.animate().alpha(1f).setDuration(460).setInterpolator(DecelerateInterpolator(1.8f)).withEndAction(cleanup).start()
                }
                "hyperos" -> { // quick fade + clock appears
                    overlay.animate().alpha(1f).setDuration(260).start()
                    val clock = TextView(this).apply {
                        text = java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault()).format(java.util.Date())
                        setTextColor(Color.WHITE); textSize = 56f; typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.BOLD)
                        alpha = 0f
                    }
                    val lp = FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT)
                    lp.gravity = android.view.Gravity.CENTER
                    root.addView(clock, lp); extras.add(clock)
                    clock.animate().alpha(1f).setDuration(220).setStartDelay(100).withEndAction {
                        clock.postDelayed({ cleanup.run() }, 350)
                    }.start()
                }
                "iphone" -> { // iOS-like shrink and fade
                    homeContent.pivotX = w / 2f; homeContent.pivotY = h / 2f
                    homeContent.animate().scaleX(bs * 0.92f).scaleY(bs * 0.92f).alpha(0f).setDuration(420)
                        .setInterpolator(DecelerateInterpolator(1.6f)).start()
                    overlay.animate().alpha(1f).setDuration(360).setStartDelay(80).withEndAction(cleanup).start()
                }
                "iphone8" -> {
                    overlay.animate().alpha(1f).setDuration(450).setInterpolator(DecelerateInterpolator(1.8f)).withEndAction(cleanup).start()
                }
                "pixel2" -> { // top and bottom curtains closing
                    val top = addOverlay(Color.BLACK, 1f)
                    val bottom = addOverlay(Color.BLACK, 1f)
                    top.pivotY = 0f; top.scaleY = 0f
                    bottom.pivotY = h; bottom.scaleY = 0f
                    val a1 = top.animate().scaleY(0.55f).setDuration(420).setInterpolator(DecelerateInterpolator(1.4f))
                    val a2 = bottom.animate().scaleY(0.55f).setDuration(420).setInterpolator(DecelerateInterpolator(1.4f))
                    a2.withEndAction { overlay.alpha = 1f; cleanup.run() }
                    a1.start(); a2.start()
                    overlay.alpha = 0f
                }
                else -> overlay.animate().alpha(1f).setDuration(420).withEndAction(cleanup).start()
            }
        } catch (_: Exception) {
            try { LockAccessibilityService.lock() } catch (_: Exception) {}
        }
    }

    private fun ensureAodPermissionAndStart() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
            AlertDialog.Builder(this)
                .setTitle("Разрешение AOD")
                .setMessage("Для Always On Display нужно разрешение «Поверх других окон».")
                .setPositiveButton("Открыть") { _, _ ->
                    try { startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, android.net.Uri.parse("package:$packageName"))) } catch (_: Exception) {}
                }
                .setNegativeButton("Отмена") { _, _ -> prefs.aodEnabled = false; findViewById<SwitchMaterial?>(R.id.aodSwitch)?.isChecked = false }
                .show()
            return
        }
        startAodOverlay()
    }

    // ---- AOD ----
    private var aodView: View? = null
    private var aodConfigView: View? = null
    private var aodTapCount = 0
    private var aodLastTap = 0L

    private fun buildAodContent(preview: Boolean): FrameLayout {
        val container = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        val clock = TextView(this).apply {
            val style = prefs.aodStyle
            val now = java.util.Date()
            text = java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault()).format(now)
            setTextColor(Color.WHITE)
            textSize = prefs.aodClockSize.toFloat()
            typeface = if (prefs.aodFontBold) android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.BOLD)
                       else android.graphics.Typeface.create("sans-serif-light", android.graphics.Typeface.NORMAL)
            if (style == "nothing") {
                typeface = android.graphics.Typeface.MONOSPACE
                letterSpacing = 0.08f
            }
        }
        val date = TextView(this).apply {
            val now = java.util.Date()
            val style = prefs.aodStyle
            text = when (style) {
                "miui13" -> java.text.SimpleDateFormat("dd.MM  EEEE", java.util.Locale("ru")).format(now)
                else -> java.text.SimpleDateFormat("EEE, dd MMM", java.util.Locale.getDefault()).format(now)
            }
            setTextColor(0xCCFFFFFF.toInt())
            textSize = if (prefs.aodStyle == "miui13") 14f else 16f
            isVisible = prefs.aodShowDate
        }
        val weather = TextView(this).apply {
            text = "22°C  Облачно"
            setTextColor(0x99FFFFFF.toInt()); textSize = 13f
            isVisible = prefs.aodStyle == "miui13"
        }

        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val g = when (prefs.aodStyle) {
                "miui13" -> android.view.Gravity.START
                else -> android.view.Gravity.CENTER_HORIZONTAL
            }
            gravity = g
            addView(clock)
            addView(date)
            addView(weather)
        }
        val lp = FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT)
        // position by percent
        container.post {
            val pw = container.width.coerceAtLeast(1)
            val ph = container.height.coerceAtLeast(1)
            lp.leftMargin = ((prefs.aodClockX / 100f) * pw - col.measuredWidth / 2f).toInt().coerceIn(0, (pw - col.measuredWidth).coerceAtLeast(0))
            lp.topMargin = ((prefs.aodClockY / 100f) * ph - col.measuredHeight / 2f).toInt().coerceIn(0, (ph - col.measuredHeight).coerceAtLeast(0))
            col.layoutParams = lp
        }
        container.addView(col, lp)

        // fingerprint icon
        if (prefs.aodFpEnabled) {
            val fp = TextView(this).apply {
                text = "◉"
                textSize = 28f
                setTextColor(when (prefs.aodFpStyle) { "fire" -> 0xFFFF5722.toInt(); "ice" -> 0xFF80DEEA.toInt(); "samsung" -> 0xFF4FC3F7.toInt(); else -> 0xCCFFFFFF.toInt() })
                alpha = 0.9f
            }
            val fpLp = FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT)
            container.post {
                val pw = container.width.coerceAtLeast(1); val ph = container.height.coerceAtLeast(1)
                fpLp.leftMargin = ((prefs.aodFpX / 100f) * pw - 40).toInt().coerceIn(0, pw - 80)
                fpLp.topMargin = ((prefs.aodFpY / 100f) * ph - 40).toInt().coerceIn(0, ph - 80)
                fp.layoutParams = fpLp
            }
            container.addView(fp, fpLp)
        }

        if (!preview) {
            container.setOnClickListener {
                val now = System.currentTimeMillis()
                if (now - aodLastTap > 600) aodTapCount = 0
                aodLastTap = now; aodTapCount++
                val need = if (prefs.aodTapToWake == "single") 1 else 2
                if (aodTapCount >= need) { aodTapCount = 0; hideAodWithAnimation() }
            }
        }
        container.alpha = 0f
        container.animate().alpha(1f).setDuration(250).start()
        return container
    }

    private fun startAodOverlay() {
        stopAodOverlay()
        try {
            val wm = getSystemService(Context.WINDOW_SERVICE) as android.view.WindowManager
            val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) android.view.WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY else @Suppress("DEPRECATION") android.view.WindowManager.LayoutParams.TYPE_PHONE
            val lp = android.view.WindowManager.LayoutParams(
                android.view.WindowManager.LayoutParams.MATCH_PARENT,
                android.view.WindowManager.LayoutParams.MATCH_PARENT, type,
                android.view.WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or android.view.WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or android.view.WindowManager.LayoutParams.FLAG_FULLSCREEN or android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON,
                android.graphics.PixelFormat.TRANSLUCENT
            )
            val v = buildAodContent(preview = false)
            // make clickable for tap-to-wake
            lp.flags = lp.flags and android.view.WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE.inv()
            wm.addView(v, lp)
            aodView = v
        } catch (_: Exception) {
            android.widget.Toast.makeText(this, "AOD: нет разрешения поверх окон", android.widget.Toast.LENGTH_SHORT).show()
        }
    }

    private fun hideAodWithAnimation() {
        val v = aodView ?: return
        v.animate().alpha(0f).setDuration(280).withEndAction { stopAodOverlay() }.start()
    }

    private fun stopAodOverlay() {
        try { aodView?.let { (getSystemService(Context.WINDOW_SERVICE) as android.view.WindowManager).removeView(it) } } catch (_: Exception) {}
        aodView = null
    }

    private fun showAodSettingsDialog() {
        openAodConfigScreen()
    }

    private fun openAodConfigScreen() {
        try { aodConfigView?.let { root.removeView(it) } } catch (_: Exception) {}
        val scrim = FrameLayout(this).apply { setBackgroundColor(0xFF000000.toInt()); isClickable = true; isFocusable = true }
        val previewBox = FrameLayout(this).apply {
            setBackgroundColor(0xFF050505.toInt())
        }
        val controls = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(12), dp(16), dp(20))
            setBackgroundColor(0xEE111111.toInt())
        }

        fun refreshPreview() {
            previewBox.removeAllViews()
            val p = buildAodContent(preview = true).apply { alpha = 1f }
            previewBox.addView(p, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        }

        fun addLabel(t: String) { controls.addView(TextView(this).apply { text = t; setTextColor(0xCCFFFFFF.toInt()); textSize = 13f }) }
        fun addBtn(t: String, onClick: (Button) -> Unit): Button { val b = Button(this).apply { text = t; setOnClickListener { onClick(this) } }; controls.addView(b); return b }
        fun addSeek(init: Int, max: Int, on: (Int) -> Unit) {
            val s = SeekBar(this); s.max = max; s.progress = init.coerceIn(0, max)
            s.setOnSeekBarChangeListener(object: SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) { if (fromUser) { on(progress); refreshPreview() } }
                override fun onStartTrackingTouch(seekBar: SeekBar?) {}
                override fun onStopTrackingTouch(seekBar: SeekBar?) {}
            }); controls.addView(s)
        }

        addLabel("Стиль AOD")
        val styleRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        listOf("hyperos" to "HyperOS", "miui13" to "MIUI 13", "nothing" to "Nothing").forEach { (k, n) ->
            styleRow.addView(Button(this).apply { text = n; setOnClickListener { prefs.aodStyle = k; refreshPreview() } },
                LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        }
        controls.addView(styleRow)

        addLabel("Размер часов"); addSeek(prefs.aodClockSize - 24, 72) { prefs.aodClockSize = it + 24 }
        addLabel("Позиция X"); addSeek(prefs.aodClockX, 100) { prefs.aodClockX = it }
        addLabel("Позиция Y"); addSeek(prefs.aodClockY, 100) { prefs.aodClockY = it }

        addBtn(if (prefs.aodFontBold) "Шрифт: жирный" else "Шрифт: тонкий") { b ->
            prefs.aodFontBold = !prefs.aodFontBold; (b as Button).text = if (prefs.aodFontBold) "Шрифт: жирный" else "Шрифт: тонкий"; refreshPreview()
        }
        addBtn(if (prefs.aodShowDate) "Дата: вкл" else "Дата: выкл") { b ->
            prefs.aodShowDate = !prefs.aodShowDate; (b as Button).text = if (prefs.aodShowDate) "Дата: вкл" else "Дата: выкл"; refreshPreview()
        }
        addBtn("Тап пробуждения: ${if (prefs.aodTapToWake=="double") "двойной" else "одинарный"}") { b ->
            prefs.aodTapToWake = if (prefs.aodTapToWake == "double") "single" else "double"
            (b as Button).text = "Тап пробуждения: ${if (prefs.aodTapToWake=="double") "двойной" else "одинарный"}"
        }

        addLabel("Отпечаток пальца")
        addBtn(if (prefs.aodFpEnabled) "Отпечаток: вкл" else "Отпечаток: выкл") { b ->
            prefs.aodFpEnabled = !prefs.aodFpEnabled; (b as Button).text = if (prefs.aodFpEnabled) "Отпечаток: вкл" else "Отпечаток: выкл"; refreshPreview()
        }
        val fpRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        listOf("xiaomi" to "Xiaomi", "samsung" to "Samsung", "fire" to "Огонь", "ice" to "Лёд").forEach { (k, n) ->
            fpRow.addView(Button(this).apply { text = n; textSize = 11f; setOnClickListener { prefs.aodFpStyle = k; refreshPreview() } },
                LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        }
        controls.addView(fpRow)
        addLabel("Отпечаток X"); addSeek(prefs.aodFpX, 100) { prefs.aodFpX = it }
        addLabel("Отпечаток Y"); addSeek(prefs.aodFpY, 100) { prefs.aodFpY = it }

        val actionRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        actionRow.addView(Button(this).apply { text = "Предпросмотр"; setOnClickListener {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this@MainActivity)) {
                ensureAodPermissionAndStart(); return@setOnClickListener
            }
            startAodOverlay()
        } }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        actionRow.addView(Button(this).apply { text = "Закрыть"; setOnClickListener {
            try { root.removeView(scrim) } catch (_: Exception) {}; aodConfigView = null
        } }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        controls.addView(actionRow)

        val scroll = android.widget.ScrollView(this).apply { addView(controls) }
        val split = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        split.addView(previewBox, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        split.addView(scroll, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, (resources.displayMetrics.heightPixels * 0.45f).toInt()))
        scrim.addView(split, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        scrim.alpha = 0f
        root.addView(scrim, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        scrim.animate().alpha(1f).setDuration(220).start()
        aodConfigView = scrim
        refreshPreview()
    }

    private fun parseAnimPoints(s: String): MutableList<AnimPoint> =
        if (s.isBlank()) mutableListOf() else s.split(";").mapNotNull { p ->
            val a = p.split(","); if (a.size < 3) null else AnimPoint(a[0].toFloatOrNull() ?: 0.5f, a[1].toFloatOrNull() ?: 0.5f, a[2].toFloatOrNull() ?: 1f)
        }.toMutableList()

    private fun saveAnimPoints(list: List<AnimPoint>): String = list.joinToString(";") { "${it.x},${it.y},${it.strength}" }

    private fun currentAnimPoints(): MutableList<AnimPoint> = if (animSimTarget == "icon") animIconPoints else animWinPoints

    private fun parseCorners(s: String): FloatArray {
        val out = FloatArray(8)
        if (s.isBlank()) return out
        val parts = s.split(";")
        for (i in 0 until minOf(4, parts.size)) {
            val a = parts[i].split(","); if (a.size >= 2) { out[i*2] = a[0].toFloatOrNull() ?: 0f; out[i*2+1] = a[1].toFloatOrNull() ?: 0f }
        }
        return out
    }

    private fun computeAnimTilt(points: List<AnimPoint>, pressure: Int, target: String = "window"): FloatArray {
        val p = pressure.coerceIn(0, 200) / 100f
        val enabled = prefs.animStudioEnabled
        var rx = 0f; var ry = 0f; var rz = 0f
        if (enabled && p > 0.01f) {
            points.forEach { pt ->
                val dx = (pt.x - 0.5f); val dy = (pt.y - 0.5f); val s = pt.strength * p
                rx += (-dy) * 35f * s; ry += dx * 35f * s; rz += dx * -8f * s
            }
            rx += prefs.animStudioPerspX.coerceIn(-100, 100) / 100f * 25f * p
            ry += prefs.animStudioPerspY.coerceIn(-100, 100) / 100f * 25f * p
            rz += prefs.animStudioPerspZ.coerceIn(-100, 100) / 100f * 20f * p
            val c = parseCorners(if (target == "icon") prefs.animStudioIconCorners else prefs.animStudioWindowCorners)
            val tlx=c[0]; val tly=c[1]; val trx=c[2]; val try_=c[3]; val brx=c[4]; val bry=c[5]; val blx=c[6]; val bly=c[7]
            rx += ((tly + try_) - (bly + bry)) * 90f * p
            ry += ((trx + brx) - (tlx + blx)) * 90f * p
            rz += ((try_ - tly) + (bry - bly)) * 30f * p
        }
        val sc = (prefs.animStudioScale.coerceIn(50,200) / 100f)
        val ox = (prefs.animStudioOffsetX.coerceIn(0,200) - 100) / 100f
        val oy = (prefs.animStudioOffsetY.coerceIn(0,200) - 100) / 100f
        return floatArrayOf(rx.coerceIn(-80f,80f), ry.coerceIn(-80f,80f), rz.coerceIn(-45f,45f), sc, ox, oy)
    }

    private fun persistAnimPoints() {
        prefs.animStudioWindowPoints = saveAnimPoints(animWinPoints)
        prefs.animStudioIconPoints = saveAnimPoints(animIconPoints)
    }

    private fun applyAnimPointsToView(view: View, points: List<AnimPoint>, pressure: Int) {
        val p = pressure.coerceIn(0, 200) / 100f
        if (!prefs.animStudioEnabled || p <= 0.01f || points.isEmpty()) {
            view.rotationX = 0f; view.rotationY = 0f; view.rotation = 0f
            view.translationX = 0f; view.translationY = 0f; view.scaleX = 1f; view.scaleY = 1f
            return
        }
        var rx = 0f; var ry = 0f; var rz = 0f; var tx = 0f; var ty = 0f
        points.forEach { pt ->
            val dx = (pt.x - 0.5f); val dy = (pt.y - 0.5f); val s = pt.strength * p
            rx += (-dy) * 35f * s
            ry += dx * 35f * s
            rz += dx * -8f * s
            tx += dx * 18f * s * resources.displayMetrics.density
            ty += dy * 18f * s * resources.displayMetrics.density
        }
        rx += prefs.animStudioPerspX.coerceIn(-100, 100) / 100f * 25f * p
        ry += prefs.animStudioPerspY.coerceIn(-100, 100) / 100f * 25f * p
        rz += prefs.animStudioPerspZ.coerceIn(-100, 100) / 100f * 20f * p
        view.cameraDistance = resources.displayMetrics.density * 6000f
        view.pivotX = view.width / 2f; view.pivotY = view.height / 2f
        view.rotationX = rx.coerceIn(-80f, 80f)
        view.rotationY = ry.coerceIn(-80f, 80f)
        view.rotation = rz.coerceIn(-45f, 45f)
        view.translationX = tx
        view.translationY = ty
        view.scaleX = 1f; view.scaleY = 1f
    }

    private fun updateAnimStudioVisibility() {
        val box = findViewById<View?>(R.id.animStudioBox) ?: return
        val on = prefs.newCloseAnim
        box.isVisible = on
        val btn = findViewById<Button?>(R.id.newAnimStudioBtn)
        btn?.alpha = if (on) 1f else 0.85f
        btn?.text = if (on) "Новые анимации: ВКЛ" else "Новые анимации: ВЫКЛ"
    }

    private fun animPresetSp() = getSharedPreferences("launcher_prefs", MODE_PRIVATE)

    private fun builtinAnimPresets(): List<Pair<String, () -> Unit>> = listOf(
        "iOS 26" to {
            prefs.animStudioEnabled = true; prefs.animStudioPressure = 120; prefs.animStudioPerspX = 20; prefs.animStudioPerspY = 0; prefs.animStudioPerspZ = 0
            animWinPoints.clear(); animWinPoints.add(AnimPoint(0.5f,0.15f,1f)); animIconPoints.clear(); animIconPoints.add(AnimPoint(0.5f,0.2f,0.8f))
            prefs.animStudioWindowCorners = ""; prefs.animStudioIconCorners = ""; persistAnimPoints()
        },
        "Samsung" to {
            prefs.animStudioEnabled = true; prefs.animStudioPressure = 60; prefs.animStudioPerspX = 0; prefs.animStudioPerspY = 0; prefs.animStudioPerspZ = 0
            animWinPoints.clear(); animIconPoints.clear(); prefs.animStudioWindowCorners = ""; prefs.animStudioIconCorners = ""; persistAnimPoints()
        },
        "Xiaomi HyperOS 5" to {
            prefs.animStudioEnabled = true; prefs.animStudioPressure = 140; prefs.animStudioPerspX = -35; prefs.animStudioPerspY = 0; prefs.animStudioPerspZ = 0
            animWinPoints.clear(); animWinPoints.add(AnimPoint(0.5f,0.85f,1f)); animWinPoints.add(AnimPoint(0.5f,0.15f,0.7f))
            animIconPoints.clear(); prefs.animStudioWindowCorners=""; prefs.animStudioIconCorners=""; persistAnimPoints()
        }
    )

    private fun showAnimPresetList(onApplied: () -> Unit) {
        val sp = animPresetSp()
        val user = sp.getStringSet("anim_user_presets", emptySet())?.toMutableList()?.sorted()?.toMutableList() ?: mutableListOf()
        val builtins = builtinAnimPresets().map { it.first }
        val names = (builtins + user.take(50 - builtins.size)).toTypedArray()
        AlertDialog.Builder(this).setTitle("Пресеты анимаций")
            .setItems(names) { _, which ->
                if (which < builtins.size) {
                    builtinAnimPresets()[which].second(); onApplied()
                } else {
                    val name = names[which]
                    val key = "anim_preset_$name"
                    sp.getString(key, null)?.let { loadAnimPresetJson(it); onApplied() }
                }
            }
            .setNegativeButton("Закрыть", null)
            .show()
    }

    private fun saveAnimPresetJson(name: String) {
        val sp = animPresetSp()
        val obj = org.json.JSONObject()
        obj.put("winPts", saveAnimPoints(animWinPoints)); obj.put("iconPts", saveAnimPoints(animIconPoints))
        obj.put("winC", prefs.animStudioWindowCorners); obj.put("iconC", prefs.animStudioIconCorners)
        obj.put("pressure", prefs.animStudioPressure); obj.put("px", prefs.animStudioPerspX); obj.put("py", prefs.animStudioPerspY); obj.put("pz", prefs.animStudioPerspZ)
        obj.put("speed", prefs.animStudioSpeed); obj.put("scale", prefs.animStudioScale); obj.put("ox", prefs.animStudioOffsetX); obj.put("oy", prefs.animStudioOffsetY)
        val set = sp.getStringSet("anim_user_presets", emptySet())?.toMutableSet() ?: mutableSetOf()
        if (set.size < 50) set.add(name)
        sp.edit().putString("anim_preset_$name", obj.toString()).putStringSet("anim_user_presets", set).apply()
    }

    private fun loadAnimPresetJson(json: String) {
        try {
            val o = org.json.JSONObject(json)
            animWinPoints.clear(); animWinPoints.addAll(parseAnimPoints(o.optString("winPts")))
            animIconPoints.clear(); animIconPoints.addAll(parseAnimPoints(o.optString("iconPts")))
            prefs.animStudioWindowCorners = o.optString("winC"); prefs.animStudioIconCorners = o.optString("iconC")
            prefs.animStudioPressure = o.optInt("pressure", 0); prefs.animStudioPerspX = o.optInt("px",0); prefs.animStudioPerspY = o.optInt("py",0); prefs.animStudioPerspZ = o.optInt("pz",0)
            prefs.animStudioSpeed = o.optInt("speed",100); prefs.animStudioScale = o.optInt("scale",100); prefs.animStudioOffsetX = o.optInt("ox",100); prefs.animStudioOffsetY = o.optInt("oy",100)
            prefs.animStudioEnabled = prefs.animStudioPressure > 0 || animWinPoints.isNotEmpty() || animIconPoints.isNotEmpty()
            persistAnimPoints()
        } catch (_: Exception) {}
    }

    private fun showPerAppAnimEditor(app: AppEntry) {
        animStudioAppKey = app.key
        settingsOverlay.isVisible = true
        showSettingsCategory("animations")
        android.widget.Toast.makeText(this, "Настройка анимации: ${app.label}. Настрой и нажми 'Для приложения'.", android.widget.Toast.LENGTH_LONG).show()
    }

    private fun applyAnimToApp(appKey: String) {
        val name = "app_${appKey.take(20)}_${System.currentTimeMillis()%10000}"
        saveAnimPresetJson(name)
        animPresetSp().edit().putString("app_anim_$appKey", name).apply()
        android.widget.Toast.makeText(this, "Анимация сохранена для приложения", android.widget.Toast.LENGTH_SHORT).show()
    }

    private fun loadAppAnimIfAny(appKey: String) {
        val sp = animPresetSp()
        val name = sp.getString("app_anim_$appKey", null) ?: return
        val json = sp.getString("anim_preset_$name", null) ?: return
        loadAnimPresetJson(json)
    }

    private fun setupAnimStudio() {
        val btn = findViewById<Button?>(R.id.newAnimStudioBtn) ?: return
        val preview = findViewById<AnimStudioPreview?>(R.id.animSimPreviewView) ?: return
        animStudioPreviewRef = preview
        val pointsLabel = findViewById<TextView?>(R.id.animSimPointsLabel) ?: return
        val pressureLabel = findViewById<TextView?>(R.id.animSimPressureLabel)
        val speedLabel = findViewById<TextView?>(R.id.animSimSpeedLabel)
        val scaleLabel = findViewById<TextView?>(R.id.animSimScaleLabel)
        val offsetLabel = findViewById<TextView?>(R.id.animSimOffsetLabel)
        val perspLabel = findViewById<TextView?>(R.id.animSimPerspLabel)
        val pressureSeek = findViewById<SeekBar?>(R.id.animSimPressureSeek) ?: return
        val speedSeek = findViewById<SeekBar?>(R.id.animSimSpeedSeek) ?: return
        val scaleSeek = findViewById<SeekBar?>(R.id.animSimScaleSeek) ?: return
        val oxSeek = findViewById<SeekBar?>(R.id.animSimOffsetXSeek) ?: return
        val oySeek = findViewById<SeekBar?>(R.id.animSimOffsetYSeek) ?: return
        val px = findViewById<SeekBar?>(R.id.animSimPerspXSeek) ?: return
        val py = findViewById<SeekBar?>(R.id.animSimPerspYSeek) ?: return
        val pz = findViewById<SeekBar?>(R.id.animSimPerspZSeek) ?: return
        val syncCorners = findViewById<SwitchMaterial?>(R.id.animSimSyncCornersSwitch)
        val fullSwitch = findViewById<SwitchMaterial?>(R.id.animSimFullscreenSwitch)

        animWinPoints.clear(); animWinPoints.addAll(parseAnimPoints(prefs.animStudioWindowPoints))
        animIconPoints.clear(); animIconPoints.addAll(parseAnimPoints(prefs.animStudioIconPoints))
        preview.points = currentAnimPoints().map { AnimStudioPreview.PPoint(it.x, it.y, it.strength) }.toMutableList()
        preview.target = animSimTarget
        preview.loadCorners(if (animSimTarget=="icon") prefs.animStudioIconCorners else prefs.animStudioWindowCorners)
        pressureSeek.progress = prefs.animStudioPressure.coerceIn(0, 200)
        speedSeek.progress = prefs.animStudioSpeed.coerceIn(20,200)
        scaleSeek.progress = prefs.animStudioScale.coerceIn(50,200)
        oxSeek.progress = prefs.animStudioOffsetX.coerceIn(0,200)
        oySeek.progress = prefs.animStudioOffsetY.coerceIn(0,200)
        px.progress = (prefs.animStudioPerspX + 100).coerceIn(0, 200)
        py.progress = (prefs.animStudioPerspY + 100).coerceIn(0, 200)
        pz.progress = (prefs.animStudioPerspZ + 100).coerceIn(0, 200)
        syncCorners?.isChecked = prefs.animStudioSyncCorners
        fullSwitch?.isChecked = prefs.animStudioFullscreen

        fun persistCorners() {
            if (animSimTarget == "icon") prefs.animStudioIconCorners = preview.cornersString() else prefs.animStudioWindowCorners = preview.cornersString()
        }
        fun presetKey(suffix: String) = "anim_studio_${prefs.animStudioPreset}_${animSimTarget}_$suffix"
        val sp = getSharedPreferences("launcher_prefs", MODE_PRIVATE)
        fun saveCurrentPreset() {
            persistAnimPoints(); persistCorners()
            sp.edit()
                .putString(presetKey("pts"), saveAnimPoints(currentAnimPoints()))
                .putString(presetKey("corners"), preview.cornersString())
                .putInt(presetKey("pressure"), prefs.animStudioPressure)
                .putInt(presetKey("px"), prefs.animStudioPerspX)
                .putInt(presetKey("py"), prefs.animStudioPerspY)
                .putInt(presetKey("pz"), prefs.animStudioPerspZ)
                .putInt(presetKey("speed"), prefs.animStudioSpeed)
                .putInt(presetKey("scale"), prefs.animStudioScale)
                .putInt(presetKey("ox"), prefs.animStudioOffsetX)
                .putInt(presetKey("oy"), prefs.animStudioOffsetY)
                .apply()
        }
        fun loadCurrentPreset() {
            val pts = sp.getString(presetKey("pts"), null)
            val corners = sp.getString(presetKey("corners"), null)
            if (pts != null) {
                val list = parseAnimPoints(pts)
                val targetList = currentAnimPoints(); targetList.clear(); targetList.addAll(list)
                persistAnimPoints()
            }
            if (corners != null) {
                if (animSimTarget == "icon") prefs.animStudioIconCorners = corners else prefs.animStudioWindowCorners = corners
                preview.loadCorners(corners)
            }
            if (sp.contains(presetKey("pressure"))) prefs.animStudioPressure = sp.getInt(presetKey("pressure"), prefs.animStudioPressure)
            if (sp.contains(presetKey("px"))) prefs.animStudioPerspX = sp.getInt(presetKey("px"), prefs.animStudioPerspX)
            if (sp.contains(presetKey("py"))) prefs.animStudioPerspY = sp.getInt(presetKey("py"), prefs.animStudioPerspY)
            if (sp.contains(presetKey("pz"))) prefs.animStudioPerspZ = sp.getInt(presetKey("pz"), prefs.animStudioPerspZ)
            if (sp.contains(presetKey("speed"))) prefs.animStudioSpeed = sp.getInt(presetKey("speed"), prefs.animStudioSpeed)
            if (sp.contains(presetKey("scale"))) prefs.animStudioScale = sp.getInt(presetKey("scale"), prefs.animStudioScale)
            if (sp.contains(presetKey("ox"))) prefs.animStudioOffsetX = sp.getInt(presetKey("ox"), prefs.animStudioOffsetX)
            if (sp.contains(presetKey("oy"))) prefs.animStudioOffsetY = sp.getInt(presetKey("oy"), prefs.animStudioOffsetY)
            pressureSeek.progress = prefs.animStudioPressure.coerceIn(0,200)
            speedSeek.progress = prefs.animStudioSpeed.coerceIn(20,200)
            scaleSeek.progress = prefs.animStudioScale.coerceIn(50,200)
            oxSeek.progress = prefs.animStudioOffsetX.coerceIn(0,200)
            oySeek.progress = prefs.animStudioOffsetY.coerceIn(0,200)
            px.progress = (prefs.animStudioPerspX + 100).coerceIn(0,200)
            py.progress = (prefs.animStudioPerspY + 100).coerceIn(0,200)
            pz.progress = (prefs.animStudioPerspZ + 100).coerceIn(0,200)
        }
        fun refreshSim(reloadCorners: Boolean = false) {
            preview.points = currentAnimPoints().map { AnimStudioPreview.PPoint(it.x, it.y, it.strength) }.toMutableList()
            preview.target = animSimTarget
            preview.pressure = prefs.animStudioPressure
            preview.perspX = prefs.animStudioPerspX; preview.perspY = prefs.animStudioPerspY; preview.perspZ = prefs.animStudioPerspZ
            if (reloadCorners) preview.loadCorners(if (animSimTarget=="icon") prefs.animStudioIconCorners else prefs.animStudioWindowCorners)
            val pLabel = when(prefs.animStudioPreset){ "open"->"открытие"; "return"->"возврат в иконку"; else->"закрытие" }
            val modeLabel = when(preview.mode){ "corners"->"углы/перспектива"; "deform"->"деформация/джин"; else->"точки нажатия" }
            pointsLabel.text = "Цель: ${if(animSimTarget=="icon") "иконка" else "окно"} • Режим: $modeLabel • Пресет: $pLabel\nТочки: окно ${animWinPoints.size}, иконка ${animIconPoints.size}. Красные = нажатие, зелёные = углы, жёлтая = цель."
            pressureLabel?.text = "Сила нажатия: ${prefs.animStudioPressure}% (0 = без наклона, 200 = сильно)"
            speedLabel?.text = "Скорость: ${prefs.animStudioSpeed}%"
            scaleLabel?.text = "Масштаб: ${prefs.animStudioScale}%"
            offsetLabel?.text = "Смещение X: ${prefs.animStudioOffsetX - 100} / Y: ${prefs.animStudioOffsetY - 100}"
            perspLabel?.text = "Перспектива X: ${prefs.animStudioPerspX} / Y: ${prefs.animStudioPerspY} / Z: ${prefs.animStudioPerspZ}"
            findViewById<Button?>(R.id.animSimModePointsBtn)?.alpha = if (preview.mode=="points") 1f else 0.5f
            findViewById<Button?>(R.id.animSimModeCornersBtn)?.alpha = if (preview.mode=="corners" || preview.mode=="deform") 1f else 0.5f
            findViewById<Button?>(R.id.animSimModeCornersBtn)?.text = if (preview.mode=="deform") "Деформ" else "Углы"
            findViewById<Button?>(R.id.animSimTargetWindowBtn)?.alpha = if (animSimTarget=="window") 1f else 0.5f
            findViewById<Button?>(R.id.animSimTargetIconBtn)?.alpha = if (animSimTarget=="icon") 1f else 0.5f
            findViewById<Button?>(R.id.animSimPresetCloseBtn)?.alpha = if (prefs.animStudioPreset=="close") 1f else 0.5f
            findViewById<Button?>(R.id.animSimPresetOpenBtn)?.alpha = if (prefs.animStudioPreset=="open") 1f else 0.5f
            findViewById<Button?>(R.id.animSimPresetReturnBtn)?.alpha = if (prefs.animStudioPreset=="return") 1f else 0.5f
            preview.invalidate()
        }

        preview.onChanged = {
            // sync points back
            val list = currentAnimPoints(); list.clear(); list.addAll(preview.points.map { AnimPoint(it.x,it.y,it.strength) })
            persistAnimPoints(); persistCorners(); refreshSim()
        }

        btn.setOnClickListener {
            prefs.newCloseAnim = !prefs.newCloseAnim
            findViewById<SwitchMaterial?>(R.id.newCloseAnimSwitch)?.isChecked = prefs.newCloseAnim
            updateAnimStudioVisibility()
        }
        updateAnimStudioVisibility()

        findViewById<Button?>(R.id.animSimModePointsBtn)?.setOnClickListener { preview.mode = "points"; refreshSim() }
        findViewById<Button?>(R.id.animSimModeCornersBtn)?.setOnClickListener {
            preview.mode = when (preview.mode) { "corners" -> "deform"; "deform" -> "corners"; else -> "corners" }
            if (preview.mode == "deform") { preview.previewProgress = 0f; android.animation.ValueAnimator.ofFloat(0f,1f).apply { duration=900; addUpdateListener { preview.previewProgress = it.animatedValue as Float; preview.invalidate() }; repeatCount=1; repeatMode=android.animation.ValueAnimator.REVERSE; start() } }
            refreshSim()
        }
        findViewById<Button?>(R.id.animSimTargetWindowBtn)?.setOnClickListener { persistCorners(); animSimTarget = "window"; refreshSim(true) }
        findViewById<Button?>(R.id.animSimTargetIconBtn)?.setOnClickListener { persistCorners(); animSimTarget = "icon"; refreshSim(true) }
        findViewById<Button?>(R.id.animSimPresetCloseBtn)?.setOnClickListener { saveCurrentPreset(); prefs.animStudioPreset = "close"; loadCurrentPreset(); refreshSim(true) }
        findViewById<Button?>(R.id.animSimPresetOpenBtn)?.setOnClickListener { saveCurrentPreset(); prefs.animStudioPreset = "open"; loadCurrentPreset(); refreshSim(true) }
        findViewById<Button?>(R.id.animSimPresetReturnBtn)?.setOnClickListener { saveCurrentPreset(); prefs.animStudioPreset = "return"; loadCurrentPreset(); refreshSim(true) }
        findViewById<Button?>(R.id.animSimSavePresetBtn)?.setOnClickListener {
            val input = android.widget.EditText(this).apply { hint = "Имя пресета"; setText(prefs.animStudioPreset) }
            AlertDialog.Builder(this).setTitle("Сохранить пресет").setView(input)
                .setPositiveButton("Сохранить") { _, _ ->
                    val name = input.text.toString().trim().ifBlank { "preset_${System.currentTimeMillis()%100000}" }
                    saveCurrentPreset(); saveAnimPresetJson(name)
                    android.widget.Toast.makeText(this, "Пресет сохранён: $name", android.widget.Toast.LENGTH_SHORT).show()
                }.setNegativeButton("Отмена", null).show()
        }
        findViewById<Button?>(R.id.animSimPresetListBtn)?.setOnClickListener {
            showAnimPresetList { loadCurrentPreset(); refreshSim(true) }
        }
        findViewById<Button?>(R.id.animSimPickIconBtn)?.setOnClickListener { try { animStudioIconPickLauncher.launch(arrayOf("image/*")) } catch (_: Exception) {} }
        findViewById<Button?>(R.id.animSimApplyAppBtn)?.setOnClickListener {
            val key = animStudioAppKey
            if (key != null) { applyAnimToApp(key) } else {
                android.widget.Toast.makeText(this, "Зажми иконку приложения → «Настроить анимацию открытия»", android.widget.Toast.LENGTH_LONG).show()
            }
        }
        findViewById<Button?>(R.id.animSimAddPointBtn)?.setOnClickListener {
            val list = currentAnimPoints(); if (list.size < 6) { list.add(AnimPoint(0.5f,0.8f,1f)); prefs.animStudioEnabled = true; persistAnimPoints(); refreshSim() }
        }
        findViewById<Button?>(R.id.animSimClearPointsBtn)?.setOnClickListener { currentAnimPoints().clear(); persistAnimPoints(); refreshSim() }
        findViewById<Button?>(R.id.animSimResetCornersBtn)?.setOnClickListener { preview.resetCorners(); persistCorners() }

        pressureSeek.setOnSeekBarChangeListener(simpleSeek { prefs.animStudioPressure = it.coerceIn(0,200); prefs.animStudioEnabled = it > 0; refreshSim() })
        speedSeek.setOnSeekBarChangeListener(simpleSeek { prefs.animStudioSpeed = it.coerceIn(20,200) })
        scaleSeek.setOnSeekBarChangeListener(simpleSeek { prefs.animStudioScale = it.coerceIn(50,200) })
        oxSeek.setOnSeekBarChangeListener(simpleSeek { prefs.animStudioOffsetX = it.coerceIn(0,200) })
        oySeek.setOnSeekBarChangeListener(simpleSeek { prefs.animStudioOffsetY = it.coerceIn(0,200) })
        px.setOnSeekBarChangeListener(simpleSeek { prefs.animStudioPerspX = it - 100; refreshSim() })
        py.setOnSeekBarChangeListener(simpleSeek { prefs.animStudioPerspY = it - 100; refreshSim() })
        pz.setOnSeekBarChangeListener(simpleSeek { prefs.animStudioPerspZ = it - 100; refreshSim() })
        syncCorners?.setOnCheckedChangeListener { _, c -> prefs.animStudioSyncCorners = c }
        fullSwitch?.setOnCheckedChangeListener { _, c -> prefs.animStudioFullscreen = c }

        findViewById<Button?>(R.id.animSimPlayCloseBtn)?.setOnClickListener {
            preview.animate().cancel(); preview.scaleX=1f; preview.scaleY=1f; preview.alpha=1f; preview.translationY=0f; preview.translationX=0f
            val d = (520f * 100f / prefs.animStudioSpeed.coerceIn(20,200)).toLong()
            preview.animate().scaleX(0.72f).scaleY(0.72f).translationY(40f*resources.displayMetrics.density).alpha(0.85f).setDuration(d/2)
                .withEndAction { preview.animate().scaleX(1f).scaleY(1f).translationY(0f).alpha(1f).setDuration(d/2).setInterpolator(DecelerateInterpolator(1.4f)).start() }
                .start()
        }
        findViewById<Button?>(R.id.animSimPlayOpenBtn)?.setOnClickListener {
            preview.animate().cancel(); preview.scaleX=0.72f; preview.scaleY=0.72f; preview.alpha=0.85f; preview.translationY=40f*resources.displayMetrics.density
            val d = (520f * 100f / prefs.animStudioSpeed.coerceIn(20,200)).toLong()
            preview.animate().scaleX(1f).scaleY(1f).translationY(0f).alpha(1f).setDuration(d).setInterpolator(OvershootInterpolator(0.9f)).start()
        }
        loadCurrentPreset()
        preview.post { refreshSim(true) }
    }

    private fun simpleSeek(onChange: (Int) -> Unit) = object : SeekBar.OnSeekBarChangeListener {
        override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) { if (fromUser) onChange(progress) }
        override fun onStartTrackingTouch(seekBar: SeekBar?) {}
        override fun onStopTrackingTouch(seekBar: SeekBar?) {}
    }

    private fun updateIconPackLabel() {
        val pkg = prefs.iconPackPackage
        iconPackLabel.text = if (pkg.isBlank()) "Пакет иконок: системный" else "Пакет иконок: $pkg"
    }

    private fun showIconEditDialog(app: AppEntry) {
        val opts = arrayOf("Из галереи", "Выбрать пакет иконок", "Сбросить иконку")
        AlertDialog.Builder(this)
            .setTitle("Иконка: ${app.label}")
            .setItems(opts) { _, which ->
                when (which) {
                    0 -> {
                        pendingIconEditKey = app.key
                        try { iconPickLauncher.launch(arrayOf("image/*")) } catch (_: Exception) {
                            pendingIconEditKey = null
                            android.widget.Toast.makeText(this, "Не удалось открыть галерею", android.widget.Toast.LENGTH_SHORT).show()
                        }
                    }
                    1 -> showIconPackPicker()
                    2 -> { prefs.clearIconOverride(app.key); loadApps() }
                }
            }.show()
    }

    private fun showIconPackPicker() {
        val packs = mutableListOf("" to "Системные")
        packs.addAll(iconPackManager.getInstalledIconPacks())
        val names = packs.map { it.second }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("Пакет иконок")
            .setItems(names) { _, which ->
                prefs.iconPackPackage = packs[which].first
                updateIconPackLabel()
                loadApps()
            }
            .show()
    }

    private fun openWallpaperPicker() {
        val opts = arrayOf("Выбрать из галереи", "Системный выбор обоев", "Живые обои")
        AlertDialog.Builder(this).setTitle("Обои")
            .setItems(opts) { _, which ->
                when (which) {
                    0 -> try { wallpaperPickLauncher.launch("image/*") } catch (_: Exception) {}
                    1 -> try { startActivity(Intent.createChooser(Intent(Intent.ACTION_SET_WALLPAPER), "Обои")) } catch (_: Exception) {}
                    2 -> try { startActivity(Intent(WallpaperManager.ACTION_LIVE_WALLPAPER_CHOOSER)) } catch (_: Exception) {}
                }
            }.show()
    }

    private fun showWallpaperTargetDialog(uri: android.net.Uri) {
        val targets = arrayOf("Домашний экран", "Экран блокировки", "Оба экрана")
        AlertDialog.Builder(this).setTitle("Установить обои")
            .setItems(targets) { _, which ->
                try {
                    val wm = WallpaperManager.getInstance(this)
                    val input = contentResolver.openInputStream(uri) ?: return@setItems
                    val bmp = android.graphics.BitmapFactory.decodeStream(input)
                    input.close()
                    if (bmp == null) return@setItems
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                        val flag = when (which) {
                            0 -> WallpaperManager.FLAG_SYSTEM
                            1 -> WallpaperManager.FLAG_LOCK
                            else -> WallpaperManager.FLAG_SYSTEM or WallpaperManager.FLAG_LOCK
                        }
                        wm.setBitmap(bmp, null, true, flag)
                    } else {
                        wm.setBitmap(bmp)
                    }
                    android.widget.Toast.makeText(this, "Обои установлены", android.widget.Toast.LENGTH_SHORT).show()
                } catch (e: Exception) {
                    android.widget.Toast.makeText(this, "Не удалось установить обои", android.widget.Toast.LENGTH_SHORT).show()
                }
            }.show()
    }

    private fun updateIconShapeLabel() {
        val label = when (prefs.iconShape) { "circle" -> "Круг"; "rounded" -> "Скруглённый"; "squircle" -> "Squircle"; "square" -> "Квадрат"; "custom" -> "Своя"; else -> prefs.iconShape }
        findViewById<TextView>(R.id.iconShapeLabel).text = "Форма иконок: $label"
    }

    private fun currentCornerRadii(): FloatArray? {
        val tl = prefs.iconRadiusTL; val tr = prefs.iconRadiusTR
        val bl = prefs.iconRadiusBL; val br = prefs.iconRadiusBR
        if (tl < 0 && tr < 0 && bl < 0 && br < 0) return null
        val d = resources.displayMetrics.density
        val maxR = 28f * d // half of 56dp
        fun r(v: Int): Float = if (v < 0) (prefs.iconRadius / 100f * maxR) else (v / 100f * maxR)
        val rtl = r(tl); val rtr = r(tr); val rbr = r(br); val rbl = r(bl)
        return floatArrayOf(rtl, rtl, rtr, rtr, rbr, rbr, rbl, rbl)
    }

    private fun showIconShapePicker() {
        val shapes = listOf("circle" to "Круг", "rounded" to "Скруглённый", "squircle" to "Squircle", "square" to "Квадрат")
        AlertDialog.Builder(this).setTitle("Форма иконок")
            .setItems(shapes.map { it.second }.toTypedArray()) { _, which ->
                prefs.iconShape = shapes[which].first
                updateIconShapeLabel()
                updateCornerPreview()
                loadApps()
            }.show()
    }

    private fun updateUnlockLabel() {
        val label = when (prefs.unlockAnim) {
            "pixel" -> "Pixel"; "poco" -> "POCO/Xiaomi"; "ios" -> "iPhone"
            "ios_old" -> "iPhone (старая iOS)"; "ios26" -> "iPhone (iOS 26)"
            "ripple" -> "Ripple (отпечаток)"; "wave" -> "Волна"; "fire" -> "Огонь"; "ice" -> "Лёд"
            "samsung" -> "Samsung One UI"; "oneplus" -> "OnePlus"; "nothing" -> "Nothing"; "hyperos" -> "HyperOS"
            "miui13" -> "MIUI 13"; "oukitel" -> "Oukitel / Чистый Android"; "none" -> "Нет"; else -> prefs.unlockAnim
        }
        findViewById<TextView>(R.id.unlockAnimLabel).text = "Анимация разблокировки: $label"
        val showOld = prefs.unlockAnim == "ios_old"
        findViewById<View?>(R.id.iosOldAnimSettings)?.isVisible = showOld
        findViewById<TextView?>(R.id.iosOldTopScaleLabel)?.text = "Масштаб верхних 3 рядов: ${prefs.iosOldTopScale}%"
        findViewById<TextView?>(R.id.iosOldBottomScaleLabel)?.text = "Масштаб нижних рядов: ${prefs.iosOldBottomScale}%"
        findViewById<TextView?>(R.id.iosOldSpeedLabel)?.text = "Скорость анимации: ${prefs.iosOldSpeed}%"
    }

    private fun showUnlockAnimPicker() {
        val opts = listOf(
            "pixel" to "Pixel", "poco" to "POCO/Xiaomi", "ios" to "iPhone",
            "ios_old" to "iPhone (старая iOS)", "ios26" to "iPhone (iOS 26)",
            "ripple" to "Ripple (отпечаток)", "wave" to "Волна", "fire" to "Огонь", "ice" to "Лёд",
            "samsung" to "Samsung One UI", "oneplus" to "OnePlus", "nothing" to "Nothing", "hyperos" to "HyperOS",
            "miui13" to "MIUI 13", "oukitel" to "Oukitel / Чистый Android", "none" to "Нет"
        )
        AlertDialog.Builder(this).setTitle("Анимация разблокировки")
            .setItems(opts.map { it.second }.toTypedArray()) { _, which ->
                prefs.unlockAnim = opts[which].first
                updateUnlockLabel()
            }.show()
    }

    private fun updateDrawerStyleLabel() {
        val label = when (prefs.drawerStyle) { "button" -> "Кнопка в доке"; else -> "Свайп вверх" }
        findViewById<TextView?>(R.id.drawerStyleLabel)?.text = "Меню приложений: $label"
    }

    private fun showDrawerStylePicker() {
        val opts = listOf("swipe" to "Свайп вверх", "button" to "Кнопка в доке")
        AlertDialog.Builder(this).setTitle("Стиль меню приложений")
            .setItems(opts.map { it.second }.toTypedArray()) { _, which ->
                prefs.drawerStyle = opts[which].first
                updateDrawerStyleLabel()
                renderDock()
                applyDrawerLayout()
            }.show()
    }

    private fun updateDrawerLayoutLabel() {
        val label = when (prefs.drawerLayout) { "list" -> "Список"; "ios" -> "iOS библиотека"; else -> "Сетка" }
        findViewById<TextView>(R.id.drawerLayoutLabel).text = "Вид меню: $label"
    }

    private fun showDrawerLayoutPicker() {
        val opts = listOf("grid" to "Сетка", "list" to "Список", "ios" to "iOS библиотека (справа, как страница)")
        AlertDialog.Builder(this).setTitle("Вид меню приложений")
            .setItems(opts.map { it.second }.toTypedArray()) { _, which ->
                prefs.drawerLayout = opts[which].first
                updateDrawerLayoutLabel()
                applyDrawerLayout()
                renderDock()
            }.show()
    }

    private fun showDrawerSettingsDialog() {
        val iosOpen = if (prefs.iosLibraryOpenMode == "swipe_up") "Снизу вверх" else "Справа (страница)"
        val opts = arrayOf(
            if (prefs.drawerIpadMode) "Режим iPad: вкл" else "Режим iPad: выкл",
            "Вид меню: ${when (prefs.drawerLayout) { "list" -> "Список"; "ios" -> "iOS библиотека"; else -> "Сетка" }}",
            "Стиль открытия: ${if (prefs.drawerStyle == "button") "Кнопка" else "Свайп"}",
            "iOS библиотека: $iosOpen"
        )
        AlertDialog.Builder(this).setTitle("Настройки меню приложений")
            .setItems(opts) { _, which ->
                when (which) {
                    0 -> { prefs.drawerIpadMode = !prefs.drawerIpadMode; renderDock() }
                    1 -> showDrawerLayoutPicker()
                    2 -> showDrawerStylePicker()
                    3 -> {
                        val modes = arrayOf("Справа (страница)", "Снизу вверх (свайп)")
                        val vals = arrayOf("page", "swipe_up")
                        AlertDialog.Builder(this).setTitle("Открытие iOS библиотеки").setItems(modes) { _, w -> prefs.iosLibraryOpenMode = vals[w]; applyDrawerLayout(); renderDock() }.show()
                    }
                }
            }.show()
    }

    private fun applyDrawerLayout() {
        if (!::drawerRecycler.isInitialized) return
        val cols = prefs.drawerGridCols.coerceIn(3,6)
        drawerRecycler.layoutManager = if (prefs.drawerLayout == "list") LinearLayoutManager(this) else GridLayoutManager(this, cols)
        drawerRecycler.adapter = drawerAdapter
        val iosDrawer = (prefs.drawerStyle == "ios" && prefs.drawerLayout != "ios") || ((prefs.drawerStyle == "ios" || prefs.drawerLayout == "ios") && prefs.iosLibraryOpenMode == "swipe_up")
        findViewById<View?>(R.id.iosLibraryContainer)?.isVisible = iosDrawer && (drawerSearch.text?.isBlank() != false)
        findViewById<View?>(R.id.drawerListContainer)?.isVisible = !iosDrawer || (drawerSearch.text?.isNotBlank() == true)
        if (iosDrawer) buildIosLibrary()
    }

    private fun updateLandDockLabel() {
        val pos = if (prefs.desktopMode) prefs.landscapeDockPosition else prefs.landscapeDockSide
        val label = when (pos) { "bottom" -> "Снизу"; "left" -> "Слева"; else -> "Справа" }
        findViewById<TextView>(R.id.landDockLabel).text = "Док в горизонтали: $label"
    }

    private fun showLandDockPicker() {
        val opts = listOf(
            "right" to "Справа",
            "left" to "Слева",
            "bottom" to "Снизу (центр)",
            "bottom-left" to "Снизу слева",
            "bottom-right" to "Снизу справа"
        )
        AlertDialog.Builder(this).setTitle("Док / положение")
            .setItems(opts.map { it.second }.toTypedArray()) { _, which ->
                val v = opts[which].first
                prefs.landscapeDockSide = v
                prefs.landscapeDockPosition = v
                updateLandDockLabel()
                applyOrientationLayout()
                renderDock()
                renderCurrentPage()
            }.show()
    }

    private fun showRefreshRatePicker(updRr: () -> Unit) {
        val modes = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) display?.supportedModes?.map { it.refreshRate.toInt() }?.distinct()?.sorted() ?: emptyList() else emptyList()
        val opts = mutableListOf(0 to "Авто")
        listOf(30,60,90,120,144,165).forEach { r -> opts.add(r to "${r} Гц${if (modes.isNotEmpty() && r !in modes) " (может не поддерживаться)" else ""}") }
        AlertDialog.Builder(this).setTitle("Частота обновления")
            .setItems(opts.map { it.second }.toTypedArray()) { _, w ->
                prefs.refreshRatePref = opts[w].first
                updRr()
                applyRefreshRatePref()
            }.show()
    }

    private fun applyRefreshRatePref() {
        try {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return
            val pref = prefs.refreshRatePref
            val w = window ?: return
            val lp = w.attributes
            if (pref == 0) { lp.preferredRefreshRate = 0f } else {
                val mode = display?.supportedModes?.minByOrNull { kotlin.math.abs(it.refreshRate - pref) }
                if (mode != null) lp.preferredDisplayModeId = mode.modeId
                lp.preferredRefreshRate = pref.toFloat()
            }
            w.attributes = lp
        } catch (_: Exception) {}
    }

    private fun applyAccent() {
        try {
            val c = prefs.accentColor
            findViewById<ImageButton>(R.id.drawerBtn).setColorFilter(c)
            settingsButton.background?.setTintList(android.content.res.ColorStateList.valueOf(0x66000000 or (c and 0x00FFFFFF)))
            updatePageIndicator()
        } catch (_: Exception) { }
    }

    private fun setupCornerSeekbars() {
        fun refreshIcons() {
            gridAdapter.notifyDataSetChanged()
            drawerAdapter.notifyDataSetChanged()
            renderDock()
            updateCornerPreview()
        }
        fun bind(id: Int, value: () -> Int, onVal: (Int) -> Unit) {
            val sb = findViewById<SeekBar>(id)
            sb.max = 100
            sb.progress = value().coerceIn(0, 100)
            sb.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                    if (fromUser) { onVal(progress); refreshIcons() }
                }
                override fun onStartTrackingTouch(seekBar: SeekBar?) {}
                override fun onStopTrackingTouch(seekBar: SeekBar?) {}
            })
        }
        bind(R.id.radiusAllSeek, { prefs.iconRadius }) { prefs.iconRadius = it; prefs.iconRadiusTL = -1; prefs.iconRadiusTR = -1; prefs.iconRadiusBL = -1; prefs.iconRadiusBR = -1 }
        bind(R.id.radiusTLSeek, { if (prefs.iconRadiusTL >= 0) prefs.iconRadiusTL else prefs.iconRadius }) { prefs.iconRadiusTL = it }
        bind(R.id.radiusTRSeek, { if (prefs.iconRadiusTR >= 0) prefs.iconRadiusTR else prefs.iconRadius }) { prefs.iconRadiusTR = it }
        bind(R.id.radiusBLSeek, { if (prefs.iconRadiusBL >= 0) prefs.iconRadiusBL else prefs.iconRadius }) { prefs.iconRadiusBL = it }
        bind(R.id.radiusBRSeek, { if (prefs.iconRadiusBR >= 0) prefs.iconRadiusBR else prefs.iconRadius }) { prefs.iconRadiusBR = it }
        findViewById<Button>(R.id.resetCornersBtn).setOnClickListener {
            prefs.iconRadius = 50; prefs.iconRadiusTL = -1; prefs.iconRadiusTR = -1; prefs.iconRadiusBL = -1; prefs.iconRadiusBR = -1
            setupCornerSeekbars(); refreshIcons()
        }
    }

    private lateinit var globalDetector: GestureDetector

    private fun updateCornerPreview() {
        try {
            val preview = findViewById<ImageView>(R.id.cornerPreviewIcon)
            val icon = packageManager.getApplicationIcon(packageName)
            preview.setImageDrawable(icon)
            AppGridAdapter.applyShape(preview, prefs.iconShape, currentCornerRadii())
            preview.invalidate()
        } catch (_: Exception) { }
    }

    private fun deviceSupportsFlagshipByDefault(): Boolean {
        val soc = (if (Build.VERSION.SDK_INT >= 31) Build.SOC_MODEL else "")?.lowercase().orEmpty()
        val hw = (Build.HARDWARE ?: "").lowercase()
        val board = (Build.BOARD ?: "").lowercase()
        val s = "$soc $hw $board"
        if (s.contains("g99") || s.contains("g100") || s.contains("dimensity") || s.contains("snapdragon 8") || s.contains("snapdragon 7")) return true
        // weak/old Helio G series below 99
        val weakTokens = listOf("g35","g37","g70","g80","g85","g88","g90","g91","g95","g96","helio g9","mt676","mt6781","mt6785")
        if (weakTokens.any { s.contains(it) }) return false
        return true
    }

    private fun applyPowerSavingMode() {
        val on = prefs.powerSaving
        homeContent.alpha = if (on) 0.72f else 1f
        homeContent.setBackgroundColor(if (on) 0x55000000 else 0x00000000)
        if (on) {
            clearBlur()
        }
        if (::gridRecycler.isInitialized) gridRecycler.alpha = if (on) 0.85f else 1f
        if (::dockBar.isInitialized) dockBar.alpha = if (on) 0.85f else 1f
        gridAdapter.notifyDataSetChanged()
        renderDock()
    }

    private fun requestWriteSettingsPermission() {
        try {
            if (android.provider.Settings.System.canWrite(this)) {
                android.widget.Toast.makeText(this, "Разрешение уже есть", android.widget.Toast.LENGTH_SHORT).show()
                return
            }
            val intent = Intent(android.provider.Settings.ACTION_MANAGE_WRITE_SETTINGS)
            intent.data = android.net.Uri.parse("package:$packageName")
            startActivity(intent)
        } catch (_: Exception) {
            android.widget.Toast.makeText(this, "Не удалось открыть настройки разрешения", android.widget.Toast.LENGTH_SHORT).show()
        }
    }

    private fun setLowBrightness() {
        try {
            if (!android.provider.Settings.System.canWrite(this)) {
                android.widget.Toast.makeText(this, "Сначала дайте разрешение на управление яркостью", android.widget.Toast.LENGTH_SHORT).show()
                requestWriteSettingsPermission()
                return
            }
            android.provider.Settings.System.putInt(contentResolver, android.provider.Settings.System.SCREEN_BRIGHTNESS_MODE, android.provider.Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL)
            android.provider.Settings.System.putInt(contentResolver, android.provider.Settings.System.SCREEN_BRIGHTNESS, 30)
            val lp = window.attributes
            lp.screenBrightness = 30f / 255f
            window.attributes = lp
            android.widget.Toast.makeText(this, "Яркость снижена", android.widget.Toast.LENGTH_SHORT).show()
        } catch (_: Exception) {
            android.widget.Toast.makeText(this, "Не удалось изменить яркость", android.widget.Toast.LENGTH_SHORT).show()
        }
    }

    private fun setupSettingsNavigation() {
        findViewById<View>(R.id.settingsBackBtn).setOnClickListener { showSettingsMain() }
        findViewById<View>(R.id.catRowAppearance).setOnClickListener { showSettingsCategory("appearance") }
        findViewById<View>(R.id.catRowThemes).setOnClickListener { showSettingsCategory("themes") }
        findViewById<View>(R.id.catRowAnimations).setOnClickListener { showSettingsCategory("animations") }
        findViewById<View>(R.id.catRowHome).setOnClickListener { showSettingsCategory("home") }
        findViewById<View>(R.id.catRowDock).setOnClickListener { showSettingsCategory("dock") }
        findViewById<View>(R.id.catRowScreen).setOnClickListener { showSettingsCategory("screen") }
        findViewById<View>(R.id.catRowDrawer).setOnClickListener { showSettingsCategory("drawer") }
        findViewById<View>(R.id.catRowPower).setOnClickListener { showSettingsCategory("power") }
        findViewById<View>(R.id.catRowSound).setOnClickListener { showSettingsCategory("sound") }
        findViewById<View>(R.id.catRowTouch).setOnClickListener { showSettingsCategory("touch") }
        findViewById<View>(R.id.catRowDesktop).setOnClickListener { showSettingsCategory("desktop") }
        findViewById<View>(R.id.catRowIsland).setOnClickListener { showSettingsCategory("island") }
        findViewById<View>(R.id.catRowAbout).setOnClickListener { showSettingsCategory("about") }
    }

    private fun showSettingsMain() {
        val landscape = resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE
        val main = findViewById<View>(R.id.settingsMain)
        val detail = findViewById<View>(R.id.settingsDetail)
        if (landscape) {
            main.isVisible = true; detail.isVisible = true
            (main.layoutParams as FrameLayout.LayoutParams).apply { width = (resources.displayMetrics.widthPixels * 0.36f).toInt(); height = FrameLayout.LayoutParams.MATCH_PARENT; gravity = android.view.Gravity.START }
            (detail.layoutParams as FrameLayout.LayoutParams).apply { width = FrameLayout.LayoutParams.MATCH_PARENT; height = FrameLayout.LayoutParams.MATCH_PARENT; marginStart = (resources.displayMetrics.widthPixels * 0.36f).toInt(); gravity = android.view.Gravity.START }
            findViewById<View>(R.id.settingsBackBtn).isVisible = false
        } else {
            main.isVisible = true; detail.isVisible = false
            (main.layoutParams as FrameLayout.LayoutParams).apply { width = FrameLayout.LayoutParams.MATCH_PARENT; height = FrameLayout.LayoutParams.MATCH_PARENT; marginStart = 0; gravity = android.view.Gravity.NO_GRAVITY }
            (detail.layoutParams as FrameLayout.LayoutParams).apply { width = FrameLayout.LayoutParams.MATCH_PARENT; height = FrameLayout.LayoutParams.MATCH_PARENT; marginStart = 0; gravity = android.view.Gravity.NO_GRAVITY }
            findViewById<View>(R.id.settingsBackBtn).isVisible = true
        }
        listOf(R.id.catAppearance, R.id.catThemes, R.id.catAnimations, R.id.catHome, R.id.catDock, R.id.catScreen, R.id.catDrawer, R.id.catPower, R.id.catSound, R.id.catTouch, R.id.catDesktop, R.id.catIsland, R.id.catAbout).forEach {
            findViewById<View>(it).isVisible = landscape && it == R.id.catAppearance
        }
        if (landscape) findViewById<TextView>(R.id.settingsDetailTitle).text = "Внешний вид"
    }

    private fun showSettingsCategory(cat: String) {
        val landscape = resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE
        val main = findViewById<View>(R.id.settingsMain)
        val detail = findViewById<View>(R.id.settingsDetail)
        val map = mapOf(
            "appearance" to (R.id.catAppearance to "Внешний вид"),
            "themes" to (R.id.catThemes to "Темы"),
            "animations" to (R.id.catAnimations to "Анимации"),
            "home" to (R.id.catHome to "Рабочий стол"),
            "dock" to (R.id.catDock to "Док-бар"),
            "screen" to (R.id.catScreen to "Экран"),
            "drawer" to (R.id.catDrawer to "Меню приложений"),
            "power" to (R.id.catPower to "Энергосбережение"),
            "sound" to (R.id.catSound to "Звук"),
            "touch" to (R.id.catTouch to "Касание"),
            "desktop" to (R.id.catDesktop to "Рабочий стол и обои"),
            "island" to (R.id.catIsland to "Dynamic Island"),
            "about" to (R.id.catAbout to "О лаунчере")
        )
        if (landscape) {
            main.isVisible = true; detail.isVisible = true
            (main.layoutParams as FrameLayout.LayoutParams).apply { width = (resources.displayMetrics.widthPixels * 0.36f).toInt(); height = FrameLayout.LayoutParams.MATCH_PARENT; marginStart = 0; gravity = android.view.Gravity.START }
            (detail.layoutParams as FrameLayout.LayoutParams).apply { width = FrameLayout.LayoutParams.MATCH_PARENT; height = FrameLayout.LayoutParams.MATCH_PARENT; marginStart = (resources.displayMetrics.widthPixels * 0.36f).toInt(); gravity = android.view.Gravity.START }
            findViewById<View>(R.id.settingsBackBtn).isVisible = false
        } else {
            main.isVisible = false; detail.isVisible = true
            (detail.layoutParams as FrameLayout.LayoutParams).apply { width = FrameLayout.LayoutParams.MATCH_PARENT; height = FrameLayout.LayoutParams.MATCH_PARENT; marginStart = 0; gravity = android.view.Gravity.NO_GRAVITY }
            findViewById<View>(R.id.settingsBackBtn).isVisible = true
        }
        map.forEach { (k, v) -> findViewById<View>(v.first).isVisible = k == cat }
        findViewById<TextView>(R.id.settingsDetailTitle).text = map[cat]?.second ?: "Настройки"
        if (cat == "about") updateAboutInfo()
    }

    private fun updateAboutInfo() {
        try {
            findViewById<TextView?>(R.id.aboutCardVersion)?.text = "Версия ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})"
            findViewById<TextView?>(R.id.aboutCardBuild)?.text = "Пакет: ${BuildConfig.APPLICATION_ID} • Сборка проверена: 1.62-beta"
            findViewById<TextView?>(R.id.aboutChangelog)?.text = """
                • Новые анимации разблокировки: iPhone (старая iOS) — ряды прилетают сверху вниз с масштабом; iPhone (iOS 26) — иконки прилетают слева/справа/снизу.
                • Исправлен drag-and-drop иконок на рабочем столе.
                • При установке приложения добавляется только оно, без массового добавления всех иконок.
                • iOS-библиотека открывается с последней страницы, даже если страница одна.
            """.trimIndent()
        } catch (_: Exception) { }
    }

    private fun setupGestures() {
        globalDetector = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDoubleTap(e: MotionEvent): Boolean {
                if (settingsOverlay.isVisible || appDrawer.isVisible) return false
                if (isPointOnAppIcon(e.rawX, e.rawY)) return false
                if (prefs.doubleTapLock) { performDoubleTapLock(); return true }
                return false
            }

            override fun onLongPress(e: MotionEvent) {
                if (settingsOverlay.isVisible || appDrawer.isVisible) return
                if (!isPointOnAppIcon(e.rawX, e.rawY)) showHomeCustomize()
            }

            override fun onFling(e1: MotionEvent?, e2: MotionEvent, velocityX: Float, velocityY: Float): Boolean {
                if (e1 == null) return false
                val dy = e2.y - e1.y
                val dx = e2.x - e1.x
                if (abs(dy) > abs(dx) && dy < -120 && abs(velocityY) > 200) {
                    if (!appDrawer.isVisible && !settingsOverlay.isVisible) {
                        val iosMode = (prefs.drawerStyle == "ios" || prefs.drawerLayout == "ios")
                        if (iosMode && prefs.iosLibraryOpenMode == "swipe_up") { openDrawer(); return true }
                        if (prefs.drawerStyle != "button" && !iosMode) { openDrawer(); return true }
                    }
                }
                if (abs(dy) > abs(dx) && dy > 120 && abs(velocityY) > 200) {
                    if (appDrawer.isVisible) {
                        val atTop = !drawerRecycler.canScrollVertically(-1)
                        val fromTopZone = e1.y < (appDrawer.top + 180 * resources.displayMetrics.density)
                        if (atTop || fromTopZone) { closeDrawer(); return true }
                    }
                }
                if (abs(dx) > abs(dy) && abs(dx) > 120 && abs(velocityX) > 200) {
                    if (!appDrawer.isVisible && !settingsOverlay.isVisible && draggedKey == null && !pageDragDidSnap) {
                        if (dx < 0) switchPageAnimated(1) else switchPageAnimated(-1)
                        return true
                    }
                }
                return false
            }
        })
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        if (prefs.touchFxEnabled && ev.actionMasked == MotionEvent.ACTION_DOWN) {
            touchFxView?.isVisible = true
            touchFxView?.addTouch(ev.rawX, ev.rawY, prefs.touchFxStyle, prefs.touchFxEnd, prefs.touchFxSize, prefs.touchFxSpeed)
        }
        handlePageDrag(ev)
        if (::globalDetector.isInitialized) globalDetector.onTouchEvent(ev)
        return super.dispatchTouchEvent(ev)
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_PICK_WIDGET) {
            if (resultCode == RESULT_OK) {
                val widgetId = data?.getIntExtra(android.appwidget.AppWidgetManager.EXTRA_APPWIDGET_ID, pendingWidgetId) ?: pendingWidgetId
                configureOrAddWidget(widgetId, data)
            } else {
                try { if (pendingWidgetId != -1) appWidgetHost?.deleteAppWidgetId(pendingWidgetId) } catch (_: Exception) {}
            }
        } else if (requestCode == REQ_CREATE_WIDGET) {
            if (resultCode == RESULT_OK) {
                val widgetId = data?.getIntExtra(android.appwidget.AppWidgetManager.EXTRA_APPWIDGET_ID, pendingWidgetId) ?: pendingWidgetId
                addWidgetView(widgetId)
            } else {
                try { if (pendingWidgetId != -1) appWidgetHost?.deleteAppWidgetId(pendingWidgetId) } catch (_: Exception) {}
            }
        }
    }

    private fun handlePageDrag(ev: MotionEvent) {
        if (appDrawer.isVisible || settingsOverlay.isVisible || draggedKey != null) {
            if (pageDragActive) snapPageDrag(0f)
            return
        }
        val iosMode = (prefs.drawerStyle == "ios" || prefs.drawerLayout == "ios") && prefs.iosLibraryOpenMode == "page"
        val targetView: View = if (iosPageVisible) findViewById(R.id.iosHomeLibrary) else gridRecycler
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                pageDragActive = false
                pageDragDidSnap = false
                pageDragStartX = ev.x
                pageDragStartY = ev.y
                pageDragDx = 0f
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = ev.x - pageDragStartX
                val dy = ev.y - pageDragStartY
                if (!pageDragActive) {
                    if (abs(dx) > 24 * resources.displayMetrics.density && abs(dx) > abs(dy) * 1.2f) {
                        if (!isPointOnAppIcon(ev.rawX - dx, ev.rawY - dy)) pageDragActive = true
                    }
                }
                if (pageDragActive) {
                    pageDragDx = dx
                    val canLeft = if (iosPageVisible) true else currentPageIndex > 0
                    val canRight = if (iosPageVisible) false else (currentPageIndex < pages.lastIndex || (iosMode && currentPageIndex == 1))
                    val resisted = when {
                        dx > 0 && !canLeft -> dx * 0.2f
                        dx < 0 && !canRight -> dx * 0.2f
                        else -> dx
                    }
                    targetView.translationX = resisted
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (pageDragActive) {
                    val dx = ev.x - pageDragStartX
                    pageDragDidSnap = true
                    snapPageDrag(dx)
                }
                pageDragActive = false
            }
        }
    }

    private fun snapPageDrag(dx: Float) {
        val w = findViewById<View>(R.id.workspaceContainer).width.toFloat().coerceAtLeast(1f)
        val threshold = w * 0.2f
        val iosMode = (prefs.drawerStyle == "ios" || prefs.drawerLayout == "ios") && prefs.iosLibraryOpenMode == "page"
        if (iosPageVisible) {
            if (dx > threshold) hideIosHomePage(true) else findViewById<View>(R.id.iosHomeLibrary).animate().translationX(0f).alpha(1f).setDuration(150).start()
            pageDragDx = 0f
            return
        }
        val goToIos = dx < -threshold && iosMode && currentPageIndex == pages.lastIndex
        val goNext = dx < -threshold && currentPageIndex < pages.lastIndex && !goToIos
        val goPrev = dx > threshold && currentPageIndex > 0
        when {
            goToIos -> showIosHomePage(true)
            goNext -> switchPageAnimated(1)
            goPrev -> switchPageAnimated(-1)
            else -> gridRecycler.animate().translationX(0f).alpha(1f).setDuration(150).start()
        }
        pageDragDx = 0f
    }

    private fun isPointOnAppIcon(rawX: Float, rawY: Float): Boolean {
        val loc = IntArray(2)
        for (i in 0 until gridRecycler.childCount) {
            val child = gridRecycler.getChildAt(i) ?: continue
            val pos = gridRecycler.getChildAdapterPosition(child)
            val hasApp = pos in 0 until pageSize && gridAdapter.getSlot(pos) != null
            if (!hasApp) continue
            val icon = child.findViewById<View?>(R.id.appIcon) ?: child
            icon.getLocationOnScreen(loc)
            if (rawX >= loc[0] && rawX <= loc[0] + icon.width && rawY >= loc[1] && rawY <= loc[1] + icon.height) return true
        }
        for (i in 0 until dockBar.childCount) {
            val child = dockBar.getChildAt(i) ?: continue
            val hasDockApp = dockApps.getOrNull(i) != null
            if (!hasDockApp) continue
            val icon = child.findViewById<View?>(R.id.appIcon) ?: child
            icon.getLocationOnScreen(loc)
            if (rawX >= loc[0] && rawX <= loc[0] + icon.width && rawY >= loc[1] && rawY <= loc[1] + icon.height) return true
        }
        return false
    }

    private fun setupBack() {
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                when {
                    aodConfigView != null -> { try { aodConfigView?.animate()?.alpha(0f)?.setDuration(180)?.withEndAction { try { root.removeView(aodConfigView) } catch (_: Exception) {}; aodConfigView = null }?.start() } catch (_: Exception) { aodConfigView = null } }
                    aodView != null -> { hideAodWithAnimation() }
                    dockFolderPopup != null -> { try { root.removeView(dockFolderPopup) } catch (_: Exception) {}; dockFolderPopup = null }
                    findViewById<View>(R.id.folderOverlay).isVisible -> findViewById<View>(R.id.folderOverlay).isVisible = false
                    findViewById<View>(R.id.iconMenuOverlay).isVisible -> {
                        findViewById<View>(R.id.iconMenuOverlay).isVisible = false
                        findViewById<View>(R.id.iconMenuPreview).isVisible = false
                        clearBlur(); if (appDrawer.isVisible) appDrawer.alpha = 1f
                    }
                    findViewById<View>(R.id.devOverlay).isVisible -> findViewById<View>(R.id.devOverlay).isVisible = false
                    findViewById<View>(R.id.aboutOverlay).isVisible -> findViewById<View>(R.id.aboutOverlay).isVisible = false
                    settingsOverlay.isVisible -> {
                        val detail = findViewById<View>(R.id.settingsDetail)
                        if (detail.isVisible) showSettingsMain() else settingsOverlay.isVisible = false
                    }
                    findViewById<View?>(R.id.homeCustomizeOverlay)?.isVisible == true -> hideHomeCustomize()
                    quickPanel.isVisible -> quickPanel.isVisible = false
                    settingsButton.isVisible -> settingsButton.isVisible = false
                    appDrawer.isVisible -> { if (iosFolderOpen != null) { iosFolderOpen = null; buildIosLibrary() } else closeDrawer() }
                    iosPageVisible -> hideIosHomePage(true)
                    else -> {}
                }
            }
        })
    }

    private fun drawerCategoryFor(app: AppEntry): String {
        val p = app.packageName.lowercase(); val l = app.label.lowercase()
        return when {
            listOf("music","spotify","ytmusic","sound","audio","deezer").any { p.contains(it) || l.contains(it) } -> "Музыка"
            listOf("video","youtube","netflix","tv","player","kino").any { p.contains(it) || l.contains(it) } -> "Видео"
            listOf("game","play.games","pubg","roblox","minecraft").any { p.contains(it) || l.contains(it) } -> "Игры"
            listOf("whats","telegram","chat","messag","insta","vk","tiktok","social").any { p.contains(it) || l.contains(it) } -> "Соцсети"
            else -> "Другое"
        }
    }

    private fun buildIosLibrary() {
        val grid = findViewById<LinearLayout?>(R.id.iosLibraryGrid) ?: return
        if (iosFolderOpen != null) {
            val name = iosFolderOpen!!
            val apps = allApps.filter { drawerCategoryFor(it) == name }
            showIosCategoryFolder(name, apps)
            return
        }
        grid.removeAllViews()
        val cats = linkedMapOf("Музыка" to mutableListOf<AppEntry>(), "Видео" to mutableListOf(), "Игры" to mutableListOf(), "Соцсети" to mutableListOf(), "Другое" to mutableListOf())
        allApps.forEach { cats[drawerCategoryFor(it)]?.add(it) }
        val entries = cats.entries.toList()
        val d = resources.displayMetrics.density
        val radius = prefs.drawerFolderRadius.coerceIn(0,40) * d
        for (i in entries.indices step 2) {
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            for (j in 0..1) {
                val idx = i + j
                val card = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    background = android.graphics.drawable.GradientDrawable().apply { cornerRadius = radius; setColor(0x33FFFFFF); setStroke((1*d).toInt(), 0x44FFFFFF) }
                    setPadding((10*d).toInt(), (10*d).toInt(), (10*d).toInt(), (10*d).toInt())
                }
                val lp = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                lp.setMargins((6*d).toInt(), (6*d).toInt(), (6*d).toInt(), (6*d).toInt())
                card.layoutParams = lp
                if (idx < entries.size) {
                    val (name, apps) = entries[idx]
                    val title = TextView(this).apply { text = name; setTextColor(Color.WHITE); textSize = 14f; setTypeface(typeface, android.graphics.Typeface.BOLD) }
                    val mini = android.widget.GridLayout(this).apply { columnCount = 2; rowCount = 2 }
                    apps.take(4).forEach { a ->
                        val iv = ImageView(this)
                        val s = (44*d).toInt()
                        val mlp = android.widget.GridLayout.LayoutParams().apply { width = s; height = s; setMargins((4*d).toInt(),(4*d).toInt(),(4*d).toInt(),(4*d).toInt()) }
                        iv.layoutParams = mlp
                        iv.setImageDrawable(a.icon)
                        mini.addView(iv)
                    }
                    card.addView(title); card.addView(mini)
                    card.setOnClickListener { if (!iosJiggleMode) showIosCategoryFolder(name, apps) }
                    card.setOnLongClickListener { iosJiggleMode = !iosJiggleMode; buildIosLibrary(); true }
                    if (iosJiggleMode) startJiggle(card) else card.clearAnimation()
                } else {
                    card.alpha = 0f
                }
                row.addView(card)
            }
            grid.addView(row)
        }
    }

    private fun showIosCategoryFolder(name: String, apps: List<AppEntry>) {
        val grid = findViewById<LinearLayout?>(R.id.iosLibraryGrid) ?: return
        iosFolderOpen = name
        grid.removeAllViews()
        val d = resources.displayMetrics.density
        val header = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = android.view.Gravity.CENTER_VERTICAL }
        val back = TextView(this).apply { text = "‹ Назад"; setTextColor(Color.WHITE); textSize = 16f; setPadding(0,0,(16*d).toInt(),0); setOnClickListener { iosFolderOpen = null; buildIosLibrary() } }
        val title = TextView(this).apply { text = name; setTextColor(Color.WHITE); textSize = 22f; setTypeface(typeface, android.graphics.Typeface.BOLD); layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f) }
        val more = TextView(this).apply { text = "⋯"; setTextColor(Color.WHITE); textSize = 22f; setPadding((12*d).toInt(),0,0,0); setOnClickListener { showIosFolderMenu(name) } }
        header.addView(back); header.addView(title); header.addView(more)
        grid.addView(header)
        val g = android.widget.GridLayout(this).apply { columnCount = prefs.drawerGridCols.coerceIn(3,6) }
        apps.forEach { app ->
            val item = layoutInflater.inflate(R.layout.item_app, g, false)
            val icon = item.findViewById<ImageView>(R.id.appIcon)
            val label = item.findViewById<TextView>(R.id.appLabel)
            icon.setImageDrawable(app.icon); label.text = app.label; label.isVisible = prefs.showLabels
            item.setOnClickListener { if (!iosJiggleMode) launchApp(app, icon, fromDrawer = true) }
            item.setOnLongClickListener { iosJiggleMode = !iosJiggleMode; buildIosLibrary(); if (iosFolderOpen!=null) showIosCategoryFolder(name, apps); true }
            if (iosJiggleMode) startJiggle(item) else item.clearAnimation()
            g.addView(item)
        }
        grid.addView(g)
    }

    private fun showIosFolderMenu(name: String) {
        val opts = arrayOf(if (iosJiggleMode) "Завершить перемещение" else "Переместить", "Уменьшенная папка", "Закрыть")
        AlertDialog.Builder(this).setTitle(name).setItems(opts) { _, w ->
            when (w) {
                0 -> { iosJiggleMode = !iosJiggleMode; buildIosLibrary() }
                1 -> { android.widget.Toast.makeText(this, "Папка уменьшена (MVP)", android.widget.Toast.LENGTH_SHORT).show() }
            }
        }.show()
    }

    private fun startJiggle(v: View) {
        val a = android.view.animation.RotateAnimation(-1.5f, 1.5f, android.view.animation.Animation.RELATIVE_TO_SELF, 0.5f, android.view.animation.Animation.RELATIVE_TO_SELF, 0.5f)
        a.duration = 120; a.repeatMode = android.view.animation.Animation.REVERSE; a.repeatCount = android.view.animation.Animation.INFINITE
        v.startAnimation(a)
    }

    private fun buildIosHomeLibrary() {
        val grid = findViewById<LinearLayout?>(R.id.iosHomeLibraryGrid) ?: return
        grid.removeAllViews()
        val cats = linkedMapOf("Музыка" to mutableListOf<AppEntry>(), "Видео" to mutableListOf(), "Игры" to mutableListOf(), "Соцсети" to mutableListOf(), "Другое" to mutableListOf())
        allApps.forEach { cats[drawerCategoryFor(it)]?.add(it) }
        val entries = cats.entries.toList()
        val d = resources.displayMetrics.density
        val radius = prefs.drawerFolderRadius.coerceIn(0,40) * d
        val title = TextView(this).apply { text = "Библиотека приложений"; setTextColor(Color.WHITE); textSize = 26f; setTypeface(typeface, android.graphics.Typeface.BOLD); setPadding(0,0,0,(12*d).toInt()) }
        grid.addView(title)
        for (i in entries.indices step 2) {
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            for (j in 0..1) {
                val idx = i + j
                val card = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    background = android.graphics.drawable.GradientDrawable().apply { cornerRadius = radius; setColor(0x33FFFFFF); setStroke((1*d).toInt(), 0x44FFFFFF) }
                    setPadding((10*d).toInt(), (10*d).toInt(), (10*d).toInt(), (10*d).toInt())
                }
                val lp = LinearLayout.LayoutParams(0, (180*d).toInt(), 1f)
                lp.setMargins((6*d).toInt(), (6*d).toInt(), (6*d).toInt(), (6*d).toInt())
                card.layoutParams = lp
                if (idx < entries.size) {
                    val (name, apps) = entries[idx]
                    val t = TextView(this).apply { text = name; setTextColor(Color.WHITE); textSize = 14f; setTypeface(typeface, android.graphics.Typeface.BOLD) }
                    val mini = android.widget.GridLayout(this).apply { columnCount = 2; rowCount = 2 }
                    apps.take(4).forEach { a ->
                        val iv = ImageView(this)
                        val s = (44*d).toInt()
                        val mlp = android.widget.GridLayout.LayoutParams().apply { width = s; height = s; setMargins((4*d).toInt(),(4*d).toInt(),(4*d).toInt(),(4*d).toInt()) }
                        iv.layoutParams = mlp; iv.setImageDrawable(a.icon); mini.addView(iv)
                    }
                    card.addView(t); card.addView(mini)
                    card.setOnClickListener { showIosHomeCategoryFolder(name, apps) }
                } else card.alpha = 0f
                row.addView(card)
            }
            grid.addView(row)
        }
    }

    private fun showIosHomeCategoryFolder(name: String, apps: List<AppEntry>) {
        val grid = findViewById<LinearLayout?>(R.id.iosHomeLibraryGrid) ?: return
        grid.removeAllViews()
        val d = resources.displayMetrics.density
        val header = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = android.view.Gravity.CENTER_VERTICAL }
        val back = TextView(this).apply { text = "‹ Назад"; setTextColor(Color.WHITE); textSize = 16f; setPadding(0,0,(16*d).toInt(),0); setOnClickListener { buildIosHomeLibrary() } }
        val title = TextView(this).apply { text = name; setTextColor(Color.WHITE); textSize = 22f; setTypeface(typeface, android.graphics.Typeface.BOLD); layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f) }
        header.addView(back); header.addView(title)
        grid.addView(header)
        val g = android.widget.GridLayout(this).apply { columnCount = prefs.drawerGridCols.coerceIn(3,6) }
        apps.forEach { app ->
            val item = layoutInflater.inflate(R.layout.item_app, g, false)
            val icon = item.findViewById<ImageView>(R.id.appIcon)
            val label = item.findViewById<TextView>(R.id.appLabel)
            icon.setImageDrawable(app.icon); label.text = app.label; label.isVisible = prefs.showLabels
            item.setOnClickListener { launchApp(app, icon, fromDrawer = false) }
            item.setOnLongClickListener {
                iosJiggleMode = true; startJiggle(item)
                hideIosHomePage(false)
                startIconDrag(app, item, fromDock = null, fromDrawer = false)
                true
            }
            g.addView(item)
        }
        grid.addView(g)
    }

    private fun openDrawer() {
        quickPanel.isVisible = false; settingsButton.isVisible = false
        applyDrawerLayout()
        val dim = (prefs.drawerDim.coerceIn(0,100) * 2.2f).toInt().coerceIn(0,220)
        appDrawer.setBackgroundColor(Color.argb(dim, 0, 0, 0))
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && prefs.drawerBlur > 0) {
            val r = (prefs.drawerBlur.coerceIn(0,100) / 100f * 30f).coerceAtLeast(0.1f)
            val eff = RenderEffect.createBlurEffect(r, r, Shader.TileMode.CLAMP)
            homeContent.setRenderEffect(eff); wallpaperBlurView?.setRenderEffect(eff)
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) { homeContent.setRenderEffect(null); wallpaperBlurView?.setRenderEffect(null) }
        appDrawer.setRenderEffect(null)
        appDrawer.alpha = 1f
        appDrawer.translationY = appDrawer.height.toFloat().takeIf { it > 0 } ?: 1400f
        appDrawer.isVisible = true
        appDrawer.animate().translationY(0f).setDuration(260).setInterpolator(DecelerateInterpolator()).start()
    }

    private fun closeDrawer() {
        val h = appDrawer.height.toFloat().takeIf { it > 0 } ?: 1400f
        appDrawer.animate().translationY(h).setDuration(220).withEndAction {
            appDrawer.isVisible = false
            appDrawer.translationY = 0f
            iosFolderOpen = null; iosJiggleMode = false
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) { appDrawer.setRenderEffect(null); homeContent.setRenderEffect(null); wallpaperBlurView?.setRenderEffect(null) }
            if (::drawerSearch.isInitialized) drawerSearch.setText("")
            drawerAdapter.submit(allApps)
        }.start()
    }

    private fun setupWelcome() {
        val overlay = findViewById<View>(R.id.welcomeOverlay)
        val start = findViewById<View>(R.id.welcomeStartBtn)
        val choices = findViewById<View>(R.id.welcomeChoices)
        val autoBtn = findViewById<View>(R.id.welcomeAutoBtn)
        val emptyBtn = findViewById<View>(R.id.welcomeEmptyBtn)
        val s1 = findViewById<View>(R.id.welcomeShape1)
        val s2 = findViewById<View>(R.id.welcomeShape2)
        val s3 = findViewById<View>(R.id.welcomeShape3)
        val title = findViewById<View>(R.id.welcomeTitle)
        val subtitle = findViewById<View>(R.id.welcomeSubtitle)
        if (prefs.welcomeDone) {
            overlay.isVisible = false
            return
        }
        overlay.isVisible = true
        listOf(s1, s2, s3, title, subtitle, start).forEach { it.alpha = 0f; it.translationY = 80f }
        s1.animate().alpha(1f).translationY(0f).setDuration(500).setStartDelay(100).start()
        s2.animate().alpha(1f).translationY(0f).setDuration(500).setStartDelay(220).start()
        s3.animate().alpha(1f).translationY(0f).setDuration(500).setStartDelay(340).start()
        title.animate().alpha(1f).translationY(0f).setDuration(450).setStartDelay(450).start()
        subtitle.animate().alpha(1f).translationY(0f).setDuration(450).setStartDelay(550).start()
        start.animate().alpha(1f).translationY(0f).setDuration(450).setStartDelay(700).start()

        val wpCheck = findViewById<android.widget.CheckBox>(R.id.welcomeWallpaperCheck)
        fun applyDefaultWallpaperIfNeeded() {
            if (!wpCheck.isChecked) return
            try {
                val bmp = android.graphics.Bitmap.createBitmap(1080, 1920, android.graphics.Bitmap.Config.ARGB_8888)
                val c = android.graphics.Canvas(bmp)
                val p = android.graphics.Paint()
                p.shader = android.graphics.LinearGradient(0f, 0f, 0f, 1920f, intArrayOf(0xFF0A0A0A.toInt(), 0xFF222222.toInt(), 0xFF050505.toInt()), floatArrayOf(0f, 0.5f, 1f), android.graphics.Shader.TileMode.CLAMP)
                c.drawRect(0f, 0f, 1080f, 1920f, p)
                p.shader = null; p.color = 0x22FFFFFF; c.drawCircle(780f, 420f, 260f, p)
                p.color = 0x14FFFFFF; c.drawCircle(260f, 1350f, 320f, p)
                WallpaperManager.getInstance(this).setBitmap(bmp)
            } catch (_: Exception) { }
        }
        fun finishWelcome() {
            prefs.welcomeDone = true
            applyDefaultWallpaperIfNeeded()
            overlay.animate().alpha(0f).setDuration(250).withEndAction {
                overlay.isVisible = false
                overlay.alpha = 1f
            }.start()
        }

        start.setOnClickListener {
            start.isVisible = false
            choices.alpha = 0f
            choices.isVisible = true
            choices.animate().alpha(1f).setDuration(250).start()
        }
        autoBtn.setOnClickListener { showAutoArrangePicker { finishWelcome() } }
        emptyBtn.setOnClickListener { applyEmptyLayout(); finishWelcome() }
    }

    private fun showAutoArrangePicker(onDone: () -> Unit) {
        val apps = allApps.toList()
        if (apps.isEmpty()) { applyAutoLayout(); onDone(); return }
        val labels = apps.map { it.label }.toTypedArray()
        val checked = BooleanArray(apps.size) { true }
        AlertDialog.Builder(this)
            .setTitle("Какие приложения добавить на рабочий стол?")
            .setMultiChoiceItems(labels, checked) { _, which, isChecked -> checked[which] = isChecked }
            .setNeutralButton("Все") { _, _ ->
                for (i in checked.indices) checked[i] = true
                applyAutoLayout(apps.filterIndexed { i, _ -> checked[i] })
                onDone()
            }
            .setPositiveButton("Готово") { _, _ ->
                applyAutoLayout(apps.filterIndexed { i, _ -> checked[i] })
                onDone()
            }
            .setNegativeButton("Отмена", null)
            .show()
    }

    private fun applyAutoLayout(selected: List<AppEntry> = allApps.toList()) {
        try {
            prefs.layoutInitialized = true
            val dockCandidates = listOf(
                listOf("dialer", "phone", "телефон", "vibe"),
                listOf("message", "sms", "mms", "сообщ"),
                listOf("browser", "chrome", "yandex", "firefox", "браузер"),
                listOf("camera", "камера")
            )
            val used = mutableSetOf<String>()
            for (i in 0 until 4) {
                val match = selected.firstOrNull { app ->
                    app.key !in used && dockCandidates[i].any { c ->
                        app.label.lowercase().contains(c) || app.packageName.lowercase().contains(c)
                    }
                }
                dockApps[i] = match
                if (match != null) used.add(match.key)
            }
            val rest = selected.filter { it.key !in used }
            pages.clear()
            if (rest.isEmpty()) {
                pages.add(newPage())
            } else {
                rest.chunked(pageSize).forEach { chunk ->
                    val page = newPage()
                    chunk.forEachIndexed { i, app -> page[i] = app }
                    pages.add(page)
                }
            }
            currentPageIndex = 0
            renderCurrentPage()
            renderDock()
            persistLayout()
        } catch (_: Exception) { }
    }

    private fun applyEmptyLayout() {
        try {
            prefs.layoutInitialized = true
            for (i in 0 until 4) dockApps[i] = null
            pages.clear()
            pages.add(newPage())
            currentPageIndex = 0
            renderCurrentPage()
            renderDock()
            persistLayout()
        } catch (_: Exception) { }
    }

    private var unlockPlayer: android.media.MediaPlayer? = null
    private fun playUnlockSound(anim: String) {
        try {
            val resId = when (anim) {
                "samsung" -> R.raw.unlock_samsung
                "ios" -> R.raw.unlock_iphone
                "oukitel", "pixel" -> R.raw.unlock_pixel
                "oneplus", "nothing" -> R.raw.unlock_oneplus
                else -> 0
            }
            if (resId == 0) return
            unlockPlayer?.release()
            unlockPlayer = android.media.MediaPlayer.create(this, resId)?.apply {
                setOnCompletionListener { it.release(); if (unlockPlayer === it) unlockPlayer = null }
                start()
            }
        } catch (_: Exception) { }
    }

    private fun playUnlockAnimation() {
        if (prefs.unlockAnim == "none") return
        val now = android.os.SystemClock.uptimeMillis()
        if (now - lastUnlockAnimAt < 600) return
        lastUnlockAnimAt = now
        root.post { runUnlockAnimation(retry = true) }
    }

    private fun runUnlockAnimation(retry: Boolean) {
        if (prefs.unlockAnim == "none") return
        try {
            val views = mutableListOf<View>()
            for (i in 0 until gridRecycler.childCount) gridRecycler.getChildAt(i)?.let { views.add(it) }
            for (i in 0 until dockBar.childCount) dockBar.getChildAt(i)?.let { views.add(it) }
            if (views.isEmpty() && retry) {
                root.postDelayed({ runUnlockAnimation(retry = false) }, 120)
                return
            }
            val centerX = root.width / 2f
            val bottomY = root.height.toFloat()
            val anim = prefs.unlockAnim
            if (anim == "oukitel" || anim == "pixel") {
                applyBlur(true)
                root.postDelayed({ clearBlur() }, 700)
            }
            playUnlockSound(anim)
            // Wallpaper blur/zoom on unlock
            val wb = prefs.wallUnlockBlur.coerceIn(0,100)
            if (wb > 0 && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val maxR = (wb / 100f) * 60f
                if (prefs.wallUnlockBlurMode == "from") {
                    val _be = android.graphics.RenderEffect.createBlurEffect(maxR.coerceAtLeast(1f), maxR.coerceAtLeast(1f), Shader.TileMode.CLAMP); homeContent.setRenderEffect(_be); wallpaperBlurView?.setRenderEffect(_be)
                    val animBlur = android.animation.ValueAnimator.ofFloat(maxR, 0.1f).apply {
                        duration = 500; addUpdateListener { v -> val r = (v.animatedValue as Float).coerceAtLeast(0.1f); val _be2 = android.graphics.RenderEffect.createBlurEffect(r,r,Shader.TileMode.CLAMP); homeContent.setRenderEffect(_be2); wallpaperBlurView?.setRenderEffect(_be2) }
                        addListener(object: android.animation.AnimatorListenerAdapter() { override fun onAnimationEnd(animation: android.animation.Animator) { homeContent.setRenderEffect(null); wallpaperBlurView?.setRenderEffect(null) } })
                    }
                    animBlur.start()
                } else {
                    val animBlur = android.animation.ValueAnimator.ofFloat(0.1f, maxR.coerceAtLeast(1f)).apply {
                        duration = 500; addUpdateListener { v -> val r = (v.animatedValue as Float).coerceAtLeast(0.1f); val _be2 = android.graphics.RenderEffect.createBlurEffect(r,r,Shader.TileMode.CLAMP); homeContent.setRenderEffect(_be2); wallpaperBlurView?.setRenderEffect(_be2) }
                        addListener(object: android.animation.AnimatorListenerAdapter() { override fun onAnimationEnd(animation: android.animation.Animator) { root.postDelayed({ homeContent.setRenderEffect(null); wallpaperBlurView?.setRenderEffect(null) }, 300) } })
                    }
                    animBlur.start()
                }
            }
            val wz = prefs.wallZoom.coerceIn(0,100)
            if (wz > 0) {
                val zf = 1f + wz/100f * 0.08f
                val fromZ = if (prefs.wallZoomMode == "in") 1f else zf
                val toZ = if (prefs.wallZoomMode == "in") zf else 1f
                homeContent.scaleX = fromZ * (prefs.screenScale.coerceIn(50,200)/100f)
                homeContent.scaleY = fromZ * (prefs.screenScale.coerceIn(50,200)/100f)
                homeContent.animate().scaleX(toZ * (prefs.screenScale.coerceIn(50,200)/100f)).scaleY(toZ * (prefs.screenScale.coerceIn(50,200)/100f)).setDuration(550).setInterpolator(DecelerateInterpolator(2f)).start()
            }

            if (anim == "ripple") {
                val ripple = View(this)
                val size = (max(root.width, root.height) * 1.2f).toInt()
                val lp = FrameLayout.LayoutParams(size, size)
                lp.gravity = android.view.Gravity.BOTTOM or android.view.Gravity.CENTER_HORIZONTAL
                lp.bottomMargin = -(size / 3)
                ripple.layoutParams = lp
                val bg = android.graphics.drawable.GradientDrawable()
                bg.shape = android.graphics.drawable.GradientDrawable.OVAL
                bg.setColor(0x55FFFFFF)
                bg.setStroke((3 * resources.displayMetrics.density).toInt(), prefs.accentColor)
                ripple.background = bg
                ripple.scaleX = 0f; ripple.scaleY = 0f; ripple.alpha = 0.9f
                root.addView(ripple)
                ripple.animate().scaleX(1f).scaleY(1f).alpha(0f).setDuration(650)
                    .setInterpolator(DecelerateInterpolator(1.2f))
                    .withEndAction { try { root.removeView(ripple) } catch (_: Exception) {} }.start()
            }
            if (anim == "fire" || anim == "ice") {
                val overlay = View(this)
                overlay.layoutParams = FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
                val top = if (anim == "fire") 0x66FF6D00 else 0x66448AFF
                val bottom = if (anim == "fire") 0x00FF1744 else 0x0000E5FF
                overlay.background = android.graphics.drawable.GradientDrawable(
                    android.graphics.drawable.GradientDrawable.Orientation.BOTTOM_TOP,
                    intArrayOf(bottom, top, 0x00000000)
                )
                overlay.alpha = 0f
                root.addView(overlay)
                overlay.animate().alpha(1f).setDuration(180).withEndAction {
                    overlay.animate().alpha(0f).setDuration(500).setStartDelay(120)
                        .withEndAction { try { root.removeView(overlay) } catch (_: Exception) {} }.start()
                }.start()
            }

            val gridChildCount = gridRecycler.childCount
            val cols = (gridRecycler.layoutManager as? GridLayoutManager)?.spanCount?.coerceAtLeast(1) ?: 4
            if ((anim == "ios_old" || anim == "ios26") && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val blurStart = 18f
                val be = RenderEffect.createBlurEffect(blurStart, blurStart, Shader.TileMode.CLAMP)
                homeContent.setRenderEffect(be); wallpaperBlurView?.setRenderEffect(be)
                android.animation.ValueAnimator.ofFloat(blurStart, 0.1f).apply {
                    duration = 650; startDelay = 120
                    addUpdateListener { va ->
                        val r = (va.animatedValue as Float).coerceAtLeast(0.1f)
                        val b = RenderEffect.createBlurEffect(r, r, Shader.TileMode.CLAMP)
                        homeContent.setRenderEffect(b); wallpaperBlurView?.setRenderEffect(b)
                    }
                    addListener(object: android.animation.AnimatorListenerAdapter() { override fun onAnimationEnd(animation: android.animation.Animator) { homeContent.setRenderEffect(null); wallpaperBlurView?.setRenderEffect(null) } })
                }.start()
            }
            views.forEachIndexed { idx, v ->
                v.animate().cancel()
                v.translationX = 0f
                when (anim) {
                    "ios" -> {
                        v.alpha = 0f; v.scaleX = 1.15f; v.scaleY = 1.15f; v.translationY = 0f
                        v.animate().alpha(1f).scaleX(1f).scaleY(1f)
                            .setStartDelay((idx * 20L).coerceAtMost(220L))
                            .setDuration(360).setInterpolator(DecelerateInterpolator(2f)).start()
                    }
                    "ios_old" -> {
                        val row = if (idx < gridChildCount) idx / cols else (gridChildCount / cols) + 2
                        val isDock = idx >= gridChildCount
                        val d = resources.displayMetrics.density
                        val speedF = (100f / prefs.iosOldSpeed.coerceIn(40, 220)).coerceIn(0.45f, 2.5f)
                        val topScale = (prefs.iosOldTopScale.coerceIn(120, 400) / 100f)
                        val bottomScale = (prefs.iosOldBottomScale.coerceIn(100, 180) / 100f)
                        if (isDock) {
                            v.alpha = 0f; v.scaleX = 1f; v.scaleY = 1f; v.translationY = 64f * d
                            v.animate().alpha(1f).translationY(0f)
                                .setStartDelay((520L * speedF).toLong())
                                .setDuration((420L * speedF).toLong().coerceAtLeast(180L))
                                .setInterpolator(DecelerateInterpolator(2.0f)).start()
                        } else if (row <= 2) {
                            v.alpha = 0f; v.scaleX = topScale; v.scaleY = topScale; v.translationY = 36f * d
                            v.animate().alpha(1f).scaleX(1f).scaleY(1f).translationY(0f)
                                .setStartDelay((row * 140L * speedF).toLong())
                                .setDuration((620L * speedF).toLong().coerceAtLeast(220L))
                                .setInterpolator(DecelerateInterpolator(2.4f)).start()
                        } else {
                            v.alpha = 0f; v.scaleX = bottomScale; v.scaleY = bottomScale; v.translationY = 44f * d
                            v.animate().alpha(1f).scaleX(1f).scaleY(1f).translationY(0f)
                                .setStartDelay((row * 110L * speedF).toLong())
                                .setDuration((520L * speedF).toLong().coerceAtLeast(200L))
                                .setInterpolator(DecelerateInterpolator(2.0f)).start()
                        }
                    }
                    "ios26" -> {
                        val row = if (idx < gridChildCount) idx / cols else (gridChildCount / cols) + 2
                        val col = if (idx < gridChildCount) idx % cols else idx - gridChildCount
                        val isDock = idx >= gridChildCount
                        val d = resources.displayMetrics.density
                        if (isDock) {
                            v.alpha = 0f; v.scaleX = 1f; v.scaleY = 1f; v.translationY = 52f * d
                            v.animate().alpha(1f).translationY(0f)
                                .setStartDelay(380L)
                                .setDuration(360)
                                .setInterpolator(DecelerateInterpolator(1.9f)).start()
                        } else {
                            val fromBottom = (row + col) % 2 == 0
                            v.alpha = 0f; v.scaleX = 1.04f; v.scaleY = 1.04f
                            v.translationX = 0f
                            v.translationY = if (fromBottom) 72f * d else 36f * d
                            v.animate().alpha(1f).scaleX(1f).scaleY(1f).translationY(0f)
                                .setStartDelay((row * 55L))
                                .setDuration(460)
                                .setInterpolator(DecelerateInterpolator(2.1f)).start()
                        }
                    }
                    "poco" -> {
                        val loc = IntArray(2); v.getLocationOnScreen(loc)
                        val vx = loc[0] + v.width / 2f - centerX
                        val vy = loc[1] + v.height / 2f - bottomY
                        val dist = kotlin.math.sqrt(vx * vx + vy * vy).coerceAtLeast(1f)
                        v.alpha = 0f; v.scaleX = 0.6f; v.scaleY = 0.6f
                        v.translationX = -vx * 0.25f; v.translationY = -vy * 0.25f
                        v.animate().alpha(1f).scaleX(1f).scaleY(1f).translationX(0f).translationY(0f)
                            .setStartDelay((dist / 4f).toLong().coerceAtMost(320L))
                            .setDuration(420).setInterpolator(OvershootInterpolator(0.9f)).start()
                    }
                    "ripple" -> {
                        val loc = IntArray(2); v.getLocationOnScreen(loc)
                        val vx = loc[0] + v.width / 2f - centerX
                        val vy = loc[1] + v.height / 2f - bottomY
                        val dist = kotlin.math.sqrt(vx * vx + vy * vy).coerceAtLeast(1f)
                        v.alpha = 0f; v.scaleX = 0.85f; v.scaleY = 0.85f; v.translationY = 40f
                        v.animate().alpha(1f).scaleX(1f).scaleY(1f).translationY(0f)
                            .setStartDelay((dist / 3f).toLong().coerceAtMost(360L))
                            .setDuration(360).setInterpolator(DecelerateInterpolator(1.8f)).start()
                    }
                    "wave" -> {
                        v.alpha = 0f; v.translationY = 80f; v.scaleX = 0.95f; v.scaleY = 0.95f
                        val col = idx % 4
                        val row = idx / 4
                        v.animate().alpha(1f).translationY(0f).scaleX(1f).scaleY(1f)
                            .setStartDelay(((col + row) * 45L).coerceAtMost(360L))
                            .setDuration(400).setInterpolator(OvershootInterpolator(0.8f)).start()
                    }
                    "fire" -> {
                        v.alpha = 0f; v.translationY = 160f; v.scaleX = 0.9f; v.scaleY = 0.9f
                        v.animate().alpha(1f).translationY(0f).scaleX(1f).scaleY(1f)
                            .setStartDelay((idx * 22L).coerceAtMost(280L))
                            .setDuration(420).setInterpolator(DecelerateInterpolator(1.4f)).start()
                    }
                    "ice" -> {
                        v.alpha = 0f; v.translationY = -60f; v.scaleX = 1.05f; v.scaleY = 1.05f
                        v.animate().alpha(1f).translationY(0f).scaleX(1f).scaleY(1f)
                            .setStartDelay((idx * 25L).coerceAtMost(300L))
                            .setDuration(450).setInterpolator(DecelerateInterpolator(2f)).start()
                    }
                    "samsung" -> {
                        // One UI: come from "distance" - small/far then zoom forward
                        v.alpha = 0f; v.scaleX = 0.6f; v.scaleY = 0.6f; v.translationY = 30f
                        v.animate().alpha(1f).scaleX(1f).scaleY(1f).translationY(0f)
                            .setStartDelay((idx * 16L).coerceAtMost(180L))
                            .setDuration(380).setInterpolator(DecelerateInterpolator(2.5f)).start()
                    }
                    "oneplus" -> {
                        v.alpha = 0f; v.scaleX = 0.92f; v.scaleY = 0.92f; v.translationY = 60f
                        v.animate().alpha(1f).scaleX(1f).scaleY(1f).translationY(0f)
                            .setStartDelay((idx * 22L).coerceAtMost(240L))
                            .setDuration(360).setInterpolator(DecelerateInterpolator(1.7f)).start()
                    }
                    "nothing" -> {
                        v.alpha = 0f; v.scaleX = 0.5f; v.scaleY = 0.5f; v.translationY = 0f
                        v.animate().alpha(1f).scaleX(1f).scaleY(1f)
                            .setStartDelay((idx * 40L).coerceAtMost(380L))
                            .setDuration(280).setInterpolator(DecelerateInterpolator(2.8f)).start()
                    }
                    "hyperos" -> {
                        // rows from bottom, start big then settle down
                        val row = idx / 4
                        v.alpha = 0f; v.scaleX = 1.18f; v.scaleY = 1.18f; v.translationY = 120f
                        v.animate().alpha(1f).scaleX(1f).scaleY(1f).translationY(0f)
                            .setStartDelay((row * 70L + (idx % 4) * 20L).coerceAtMost(420L))
                            .setDuration(480).setInterpolator(DecelerateInterpolator(1.6f)).start()
                    }
                    "miui13" -> {
                        val row = idx / 4
                        v.alpha = 0f; v.scaleX = 0.9f; v.scaleY = 0.9f; v.translationY = 140f
                        v.animate().alpha(1f).scaleX(1f).scaleY(1f).translationY(0f)
                            .setStartDelay((row * 55L + (idx % 4) * 18L).coerceAtMost(360L))
                            .setDuration(420).setInterpolator(DecelerateInterpolator(1.8f)).start()
                    }
                    "oukitel" -> {
                        v.alpha = 0f; v.translationY = 90f * resources.displayMetrics.density / 3f; v.scaleX = 0.98f; v.scaleY = 0.98f
                        v.animate().alpha(1f).translationY(0f).scaleX(1f).scaleY(1f)
                            .setStartDelay((idx * 18L).coerceAtMost(220L))
                            .setDuration(420).setInterpolator(DecelerateInterpolator(2f)).start()
                    }
                    else -> { // pixel
                        v.alpha = 0f; v.translationY = 120f; v.scaleX = 0.92f; v.scaleY = 0.92f
                        v.animate().alpha(1f).translationY(0f).scaleX(1f).scaleY(1f)
                            .setStartDelay((idx * 28L).coerceAtMost(300L))
                            .setDuration(380).setInterpolator(DecelerateInterpolator(1.6f)).start()
                    }
                }
            }
        } catch (_: Exception) { }
    }

    private fun playCameraGenieClose() {
        try {
            val d = resources.displayMetrics.density
            val overlay = View(this)
            overlay.layoutParams = FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
            overlay.setBackgroundColor(0xFF000000.toInt())
            overlay.pivotX = root.width.toFloat() - 40f * d
            overlay.pivotY = root.height.toFloat() - 140f * d
            overlay.alpha = 1f
            root.addView(overlay)
            overlay.animate().scaleX(0.05f).scaleY(0.05f).alpha(0f).rotation(-8f).setDuration(320)
                .setInterpolator(DecelerateInterpolator(1.8f))
                .withEndAction { try { root.removeView(overlay) } catch (_: Exception) {} }.start()
        } catch (_: Exception) { }
    }

    private fun launchCameraWithGenie() {
        try {
            val d = resources.displayMetrics.density
            val pulse = View(this)
            val size = (44 * d).toInt()
            val lp = FrameLayout.LayoutParams(size, size)
            lp.gravity = android.view.Gravity.BOTTOM or android.view.Gravity.END
            lp.marginEnd = (20 * d).toInt()
            lp.bottomMargin = (120 * d).toInt()
            pulse.layoutParams = lp
            val bg = android.graphics.drawable.GradientDrawable()
            bg.shape = android.graphics.drawable.GradientDrawable.OVAL
            bg.setColor(prefs.accentColor)
            pulse.background = bg
            pulse.alpha = 0f
            pulse.scaleX = 0.6f; pulse.scaleY = 0.6f
            root.addView(pulse)
            pulse.animate().alpha(0.9f).scaleX(1f).scaleY(1f).setDuration(140).withEndAction {
                pulse.animate().alpha(0f).scaleX(1.6f).scaleY(1.6f).setDuration(220).withEndAction {
                    try { root.removeView(pulse) } catch (_: Exception) {}
                }.start()

                // Genie-like camera launch overlay
                val overlay = View(this)
                overlay.layoutParams = FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
                overlay.setBackgroundColor(0xFF000000.toInt())
                overlay.pivotX = root.width.toFloat() - 40f * d
                overlay.pivotY = root.height.toFloat() - 140f * d
                overlay.scaleX = 0.05f; overlay.scaleY = 0.05f; overlay.alpha = 0f
                overlay.rotation = -8f
                root.addView(overlay)
                overlay.animate().scaleX(1f).scaleY(1f).alpha(1f).rotation(0f).setDuration(320)
                    .setInterpolator(DecelerateInterpolator(1.8f))
                    .withEndAction {
                        try {
                            val camIntent = Intent(android.provider.MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA)
                            camIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            cameraGeniePending = true
                            startActivity(camIntent)
                        } catch (_: Exception) {
                            try { startActivity(Intent(android.provider.MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA)) } catch (_: Exception) {}
                        }
                        overlay.animate().alpha(0f).setDuration(250).setStartDelay(200).withEndAction {
                            try { root.removeView(overlay) } catch (_: Exception) {}
                        }.start()
                    }.start()
            }.start()
        } catch (_: Exception) {
            try { startActivity(Intent(android.provider.MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA)) } catch (_: Exception) {}
        }
    }

    private fun animateShatter(target: View, onEnd: () -> Unit) {
        try {
            val bmp = target.drawToBitmap()
            val loc = IntArray(2); target.getLocationOnScreen(loc)
            val rootLoc = IntArray(2); root.getLocationOnScreen(rootLoc)
            val left = loc[0] - rootLoc[0]
            val top = loc[1] - rootLoc[1]
            val cols = 8; val rows = 8
            val pw = (bmp.width / cols).coerceAtLeast(1)
            val ph = (bmp.height / rows).coerceAtLeast(1)
            val pieces = mutableListOf<View>()
            val cx = left + bmp.width / 2f
            val cy = top + bmp.height / 2f
            for (r in 0 until rows) {
                for (c in 0 until cols) {
                    val px = c * pw; val py = r * ph
                    val w = if (c == cols - 1) bmp.width - px else pw
                    val h = if (r == rows - 1) bmp.height - py else ph
                    if (w <= 0 || h <= 0) continue
                    val pieceBmp = android.graphics.Bitmap.createBitmap(bmp, px, py, w, h)
                    val iv = ImageView(this)
                    iv.setImageBitmap(pieceBmp)
                    iv.x = (left + px).toFloat(); iv.y = (top + py).toFloat()
                    iv.layoutParams = FrameLayout.LayoutParams(w, h)
                    root.addView(iv)
                    pieces.add(iv)
                }
            }
            target.alpha = 0f
            val rnd = java.util.Random()
            pieces.forEach { p ->
                val pcx = p.x + p.width / 2f; val pcy = p.y + p.height / 2f
                val vx = pcx - cx; val vy = pcy - cy
                val base = (rnd.nextFloat() * 180f + 80f)
                val dx = vx * 0.6f + (rnd.nextFloat() - 0.5f) * base
                val dy = vy * 0.6f + (rnd.nextFloat() - 0.3f) * base
                val rot = (rnd.nextFloat() - 0.5f) * 360f
                p.animate().translationXBy(dx).translationYBy(dy).rotationBy(rot)
                    .alpha(0f).scaleX(0.15f).scaleY(0.15f).setDuration(380)
                    .setInterpolator(DecelerateInterpolator(1.2f)).start()
            }
            // recoil nearby icons
            val allViews = mutableListOf<View>()
            for (i in 0 until gridRecycler.childCount) gridRecycler.getChildAt(i)?.let { allViews.add(it) }
            for (i in 0 until dockBar.childCount) dockBar.getChildAt(i)?.let { allViews.add(it) }
            val tLoc = IntArray(2); target.getLocationOnScreen(tLoc)
            val tcx = tLoc[0] + target.width / 2f; val tcy = tLoc[1] + target.height / 2f
            allViews.forEach { v ->
                if (v === target) return@forEach
                val vl = IntArray(2); v.getLocationOnScreen(vl)
                val vcx = vl[0] + v.width / 2f; val vcy = vl[1] + v.height / 2f
                val ddx = vcx - tcx; val ddy = vcy - tcy
                val dist = kotlin.math.sqrt(ddx*ddx + ddy*ddy)
                if (dist < 280f * resources.displayMetrics.density) {
                    val push = ((1f - dist / (280f * resources.displayMetrics.density)) * 10f * resources.displayMetrics.density)
                    val nx = if (kotlin.math.abs(ddx) > kotlin.math.abs(ddy)) (if (ddx > 0) push else -push) else 0f
                    val ny = if (kotlin.math.abs(ddy) >= kotlin.math.abs(ddx)) (if (ddy > 0) push else -push) else 0f
                    v.animate().translationXBy(nx).translationYBy(ny).setDuration(90).withEndAction {
                        v.animate().translationX(0f).translationY(0f).setDuration(180).setInterpolator(DecelerateInterpolator(2f)).start()
                    }.start()
                }
            }
            root.postDelayed({
                pieces.forEach { try { root.removeView(it) } catch (_: Exception) {} }
                onEnd()
            }, 400)
        } catch (_: Exception) { onEnd() }
    }

    private fun showHomeCustomize() {
        if (settingsOverlay.isVisible || appDrawer.isVisible) return
        val overlay = findViewById<View>(R.id.homeCustomizeOverlay)
        overlay.alpha = 0f; overlay.isVisible = true
        overlay.animate().alpha(1f).setDuration(180).start()
        quickPanel.isVisible = false; settingsButton.isVisible = false
    }

    private fun hideHomeCustomize() {
        val overlay = findViewById<View?>(R.id.homeCustomizeOverlay) ?: return
        overlay.animate().alpha(0f).setDuration(150).withEndAction { overlay.isVisible = false; overlay.alpha = 1f }.start()
    }

    private fun setupHomeCustomize() {
        val overlay = findViewById<View?>(R.id.homeCustomizeOverlay) ?: return
        overlay.setOnClickListener { hideHomeCustomize() }
        findViewById<View?>(R.id.homeCustomizeSheet)?.setOnClickListener { }
        findViewById<View?>(R.id.homeCustomizeSettingsBtn)?.setOnClickListener { hideHomeCustomize(); showSettingsMain(); settingsOverlay.isVisible = true }
        findViewById<View?>(R.id.homeCustomizeAddWidgetBtn)?.setOnClickListener { hideHomeCustomize(); requestAddWidget() }
        val refresh = { gridAdapter.notifyDataSetChanged(); drawerAdapter.notifyDataSetChanged(); renderDock(); renderCurrentPage() }
        findViewById<View?>(R.id.iconTintDefaultBtn)?.setOnClickListener { prefs.iconTintMode = "default"; refresh() }
        findViewById<View?>(R.id.iconTintLightBtn)?.setOnClickListener { prefs.iconTintMode = "light"; refresh() }
        findViewById<View?>(R.id.iconTintDarkBtn)?.setOnClickListener { prefs.iconTintMode = "dark"; refresh() }
        findViewById<View?>(R.id.iconTintTransparentBtn)?.setOnClickListener { prefs.iconTintMode = "transparent"; refresh() }
        findViewById<View?>(R.id.iconTintColorBtn)?.setOnClickListener {
            val colors = intArrayOf(0xFFFFFFFF.toInt(), 0xFF000000.toInt(), 0xFFFF5252.toInt(), 0xFF448AFF.toInt(), 0xFF69F0AE.toInt(), 0xFFFFD740.toInt(), 0xFFE040FB.toInt())
            val names = arrayOf("Белый","Чёрный","Красный","Синий","Зелёный","Жёлтый","Фиолетовый")
            AlertDialog.Builder(this).setTitle("Цвет иконок").setItems(names) { _, w -> prefs.iconTintMode = "color"; prefs.iconTintColor = colors[w]; refresh() }.show()
        }
        findViewById<View?>(R.id.iconSizeSmallBtn)?.setOnClickListener { prefs.iconScale = 100; prefs.showLabels = true; refresh() }
        findViewById<View?>(R.id.iconSizeBigBtn)?.setOnClickListener { prefs.iconScale = 130; prefs.showLabels = false; refresh() }
    }

    private fun requestAddWidget() {
        try {
            val host = appWidgetHost ?: return
            val id = host.allocateAppWidgetId()
            pendingWidgetId = id
            val pick = Intent(android.appwidget.AppWidgetManager.ACTION_APPWIDGET_PICK)
            pick.putExtra(android.appwidget.AppWidgetManager.EXTRA_APPWIDGET_ID, id)
            startActivityForResult(pick, REQ_PICK_WIDGET)
        } catch (e: Exception) {
            android.widget.Toast.makeText(this, "Не удалось открыть виджеты: ${e.message}", android.widget.Toast.LENGTH_SHORT).show()
        }
    }

    private fun configureOrAddWidget(widgetId: Int, data: Intent?) {
        try {
            val mgr = android.appwidget.AppWidgetManager.getInstance(this)
            val info = mgr.getAppWidgetInfo(widgetId) ?: return
            if (info.configure != null) {
                pendingWidgetId = widgetId
                val i = Intent(android.appwidget.AppWidgetManager.ACTION_APPWIDGET_CONFIGURE)
                i.component = info.configure
                i.putExtra(android.appwidget.AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId)
                startActivityForResult(i, REQ_CREATE_WIDGET)
            } else {
                addWidgetView(widgetId)
            }
        } catch (_: Exception) { addWidgetView(widgetId) }
    }

    private fun saveWidgetIds() {
        val container = findViewById<LinearLayout?>(R.id.widgetContainer) ?: return
        val ids = mutableListOf<Int>()
        for (i in 0 until container.childCount) {
            val v = container.getChildAt(i)
            val id = (v as? android.appwidget.AppWidgetHostView)?.appWidgetId ?: continue
            ids.add(id)
        }
        prefs.homeWidgetIds = ids.joinToString(",")
    }

    private fun restoreWidgets() {
        val ids = prefs.homeWidgetIds.split(",").mapNotNull { it.trim().toIntOrNull() }
        ids.forEach { addWidgetView(it, persist = false) }
    }

    private fun addWidgetView(widgetId: Int, persist: Boolean = true) {
        try {
            val host = appWidgetHost ?: return
            val mgr = android.appwidget.AppWidgetManager.getInstance(this)
            val info = mgr.getAppWidgetInfo(widgetId) ?: return
            val view = host.createView(this, widgetId, info)
            view.setAppWidget(widgetId, info)
            val container = findViewById<LinearLayout?>(R.id.widgetContainer) ?: return
            container.isVisible = true
            val h = if (info.minHeight > 0) info.minHeight else 180
            val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, (h * resources.displayMetrics.density).toInt().coerceAtLeast((120*resources.displayMetrics.density).toInt()))
            lp.bottomMargin = (8 * resources.displayMetrics.density).toInt()
            view.layoutParams = lp
            view.setOnLongClickListener {
                AlertDialog.Builder(this).setTitle("Виджет").setItems(arrayOf("Удалить")) { _, _ ->
                    try { container.removeView(view); host.deleteAppWidgetId(widgetId) } catch (_: Exception) {}
                    container.isVisible = container.childCount > 0
                    saveWidgetIds()
                }.show(); true
            }
            container.addView(view)
            if (persist) { saveWidgetIds(); android.widget.Toast.makeText(this, "Виджет добавлен", android.widget.Toast.LENGTH_SHORT).show() }
        } catch (_: Exception) { }
    }

    private fun showSettingsButton() {
        if (settingsOverlay.isVisible || appDrawer.isVisible) return
        quickPanel.bringToFront()
        quickPanel.alpha = 0f
        quickPanel.scaleX = 0.88f
        quickPanel.scaleY = 0.88f
        quickPanel.translationY = 120f
        quickPanel.isVisible = true
        quickPanel.animate().alpha(1f).scaleX(1f).scaleY(1f).translationY(0f)
            .setDuration(260).setInterpolator(DecelerateInterpolator(1.6f)).start()
        settingsButton.isVisible = false
    }

    private fun applyIconOverride(key: String, fallback: android.graphics.drawable.Drawable): android.graphics.drawable.Drawable {
        val uriStr = prefs.getIconOverride(key)
        if (uriStr.isBlank()) return fallback
        return try {
            val uri = android.net.Uri.parse(uriStr)
            contentResolver.openInputStream(uri)?.use { input ->
                android.graphics.drawable.Drawable.createFromStream(input, uri.toString()) ?: fallback
            } ?: fallback
        } catch (_: Exception) { fallback }
    }

    private fun handlePackageChange(pkg: String, action: String) {
        try { loadApps(addMissingToHome = action == Intent.ACTION_PACKAGE_ADDED, addedPackage = if (action == Intent.ACTION_PACKAGE_ADDED) pkg else null) } catch (_: Exception) {}
    }

    private fun loadApps(addMissingToHome: Boolean = false, addedPackage: String? = null) {
        val pack = prefs.iconPackPackage
        Thread {
            try {
                val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
                val resolved = packageManager.queryIntentActivities(intent, 0)
                val apps = resolved.mapNotNull { ri ->
                    try {
                        val label = ri.loadLabel(packageManager)?.toString()?.trim().orEmpty()
                        val pkg = ri.activityInfo?.packageName ?: return@mapNotNull null
                        val act = ri.activityInfo?.name ?: return@mapNotNull null
                        val defIcon = ri.loadIcon(packageManager) ?: return@mapNotNull null
                        if (pkg == packageName) return@mapNotNull null
                        val component = "$pkg/$act"
                        val baseIcon = runCatching { iconPackManager.loadIcon(pack, component, defIcon) }.getOrDefault(defIcon)
                        val icon = applyIconOverride(component, baseIcon)
                        AppEntry(label, pkg, act, icon)
                    } catch (_: Exception) { null }
                }.distinctBy { it.key }.sortedBy { it.label.lowercase() }

                root.post {
                    try {
                        allApps.clear()
                        allApps.addAll(apps)
                        val byKey = apps.associateBy { it.key }
                        if (prefs.layoutInitialized) {
                            val mode = layoutMode()
                            val savedDock = prefs.loadDockOrder(mode).mapNotNull { byKey[it] }.distinctBy { it.key }.take(4)
                            for (i in 0 until 4) dockApps[i] = savedDock.getOrNull(i)
                            val used = dockApps.filterNotNull().map { it.key }.toMutableSet()
                            val savedPages = prefs.loadPages(mode)
                            pages.clear()
                            if (savedPages.isNotEmpty()) {
                                savedPages.forEach { pageKeys ->
                                    val page = MutableList<AppEntry?>(pageSize) { null }
                                    for (i in 0 until pageSize) {
                                        val k = pageKeys.getOrNull(i)
                                        val app = when {
                                            k == null -> null
                                            isFolderKey(k) -> buildFolderEntry(folderIdFromKey(k))
                                            else -> byKey[k]
                                        }
                                        if (app != null && app.key !in used) {
                                            page[i] = app
                                            used.add(app.key)
                                        }
                                    }
                                    pages.add(page)
                                }
                            }
                            if (pages.isEmpty()) pages.add(newPage())
                            if (addMissingToHome && !addedPackage.isNullOrBlank()) {
                                val missing = apps.filter { it.key !in used && it.packageName == addedPackage }
                                for (app in missing) {
                                    var placed = false
                                    for (p in pages) {
                                        val idx = p.indexOfFirst { it == null }
                                        if (idx >= 0) { p[idx] = app; used.add(app.key); placed = true; break }
                                    }
                                    if (!placed) { val np = newPage(); np[0] = app; pages.add(np); used.add(app.key) }
                                }
                            }
                            while (pages.size > 1 && pages.last().all { it == null }) pages.removeAt(pages.lastIndex)
                            currentPageIndex = prefs.currentPage.coerceIn(0, pages.lastIndex)
                            renderCurrentPage()
                        } else {
                            for (i in 0 until 4) dockApps[i] = null
                            pages.clear()
                            pages.add(newPage())
                            currentPageIndex = 0
                            renderCurrentPage()
                        }
                        drawerAdapter.submit(allApps)
                        renderDock()
                        lastLayoutMode = layoutMode()
                        if (prefs.layoutInitialized) persistLayout()
                    } catch (_: Exception) { }
                }
            } catch (_: Exception) { }
        }.start()
    }

    private fun renderDock() {
        dockBar.removeAllViews()
        dockBar.clipChildren = false
        dockBar.clipToPadding = false
        dockBar.gravity = android.view.Gravity.CENTER
        val density = resources.displayMetrics.density
        val vertical = dockBar.orientation == LinearLayout.VERTICAL
        val landscape = resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE
        val dockPos = currentDockPosition(landscape)
        val compactBottom = !vertical && (dockPos == "bottom-left" || dockPos == "bottom-right")
        val ipad = prefs.drawerIpadMode || (landscape && prefs.desktopMode)
        val slots = if (ipad) 5 else 4
        val foldPortraitSide = vertical && !landscape && prefs.dockLayout == "orbit"
        for (i in 0 until slots) {
            val item = layoutInflater.inflate(R.layout.item_app, dockBar, false)
            val icon = item.findViewById<ImageView>(R.id.appIcon)
            val label = item.findViewById<TextView>(R.id.appLabel)
            label.visibility = View.GONE
            (item as? LinearLayout)?.gravity = android.view.Gravity.CENTER
            val lp = if (vertical) {
                if (foldPortraitSide) LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, (72 * density).toInt())
                else LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f)
            } else if (compactBottom) {
                LinearLayout.LayoutParams((64 * density).toInt(), LinearLayout.LayoutParams.MATCH_PARENT)
            } else {
                val dockW = if (dockBar.width > 0) dockBar.width else (resources.displayMetrics.widthPixels - density * 56).toInt()
                val itemWidth = (dockW / slots).coerceAtLeast((56*density).toInt())
                LinearLayout.LayoutParams(itemWidth, LinearLayout.LayoutParams.MATCH_PARENT)
            }
            item.layoutParams = lp
            val scale = prefs.iconScale.coerceIn(60, 140) / 100f
            val base = dp(56)
            val sz = (base * scale).toInt().coerceAtLeast(1)
            val ilp = icon.layoutParams
            ilp.width = sz; ilp.height = sz; icon.layoutParams = ilp
            (item as? android.view.ViewGroup)?.let { it.clipChildren = false; it.clipToPadding = false }
            if (ipad && i == 4) {
                icon.setImageDrawable(getDockFolderIcon())
                AppGridAdapter.applyShape(icon, prefs.iconShape, currentCornerRadii())
                item.alpha = 1f
                item.setOnClickListener { showDockFolderPopup(item) }
                item.setOnLongClickListener { showDrawerSettingsDialog(); true }
            } else {
                val app = dockApps[i]
                if (app != null) {
                    icon.setImageDrawable(app.icon)
                    label.text = app.label
                    AppGridAdapter.applyShape(icon, prefs.iconShape, currentCornerRadii())
                    item.setOnClickListener { if (isFolderKey(app.key)) openFolder(folderIdFromKey(app.key)) else launchApp(app, icon, fromDrawer = false) }
                    item.setOnLongClickListener { showIconMenu(app, item, fromDock = i, fromDrawer = false) }
                    item.alpha = 1f
                } else {
                    item.alpha = 0f
                }
                item.setOnDragListener { _, event -> handleDockSlotDrag(i, event) }
            }
            AppGridAdapter.attachTilt(item, { prefs.tiltAnimation }, { prefs.tiltIntensity })
            dockBar.addView(item)
        }
        val isEmptyDock = dockApps.all { it == null }
        if (isEmptyDock && (foldPortraitSide || compactBottom || prefs.dockLayout == "orbit")) {
            val ph = TextView(this).apply {
                text = "+"; setTextColor(0x88FFFFFF.toInt()); textSize = 22f; gravity = android.view.Gravity.CENTER
                background = android.graphics.drawable.GradientDrawable().apply { cornerRadius = 16f * density; setColor(0x22FFFFFF); setStroke((1*density).toInt(), 0x55FFFFFF) }
            }
            val sz = (56 * density).toInt()
            val lp2 = if (vertical) LinearLayout.LayoutParams((56*density).toInt(), sz) else LinearLayout.LayoutParams(sz, sz)
            lp2.gravity = android.view.Gravity.CENTER
            ph.layoutParams = lp2
            dockBar.addView(ph, 0)
        }
        val drawerBtn = findViewById<ImageButton>(R.id.drawerBtn)
        drawerBtn.isVisible = prefs.drawerStyle == "button" && prefs.drawerLayout != "ios" && !ipad
        drawerBtn.setColorFilter(prefs.accentColor)
        drawerBtn.setOnClickListener { if (appDrawer.isVisible) closeDrawer() else openDrawer() }
    }

    private fun loadFoldersFromPrefs() {
        try {
            val arr = org.json.JSONArray(prefs.loadFolders())
            folders.clear(); folderNames.clear(); folderTypes.clear()
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val id = o.optString("id"); if (id.isBlank()) continue
                folderNames[id] = o.optString("name", "Папка")
                folderTypes[id] = o.optString("type", "normal")
                val apps = mutableListOf<String>()
                val a = o.optJSONArray("apps") ?: org.json.JSONArray()
                for (j in 0 until a.length()) a.optString(j)?.takeIf { it.isNotBlank() }?.let { apps.add(it) }
                folders[id] = apps
            }
        } catch (_: Exception) { folders.clear(); folderNames.clear(); folderTypes.clear() }
    }

    private fun saveFoldersToPrefs() {
        try {
            val arr = org.json.JSONArray()
            folders.forEach { (id, apps) ->
                val o = org.json.JSONObject()
                o.put("id", id); o.put("name", folderNames[id] ?: "Папка")
                o.put("type", folderTypes[id] ?: "normal")
                val a = org.json.JSONArray(); apps.forEach { a.put(it) }; o.put("apps", a)
                arr.put(o)
            }
            prefs.saveFolders(arr.toString())
        } catch (_: Exception) { }
    }

    private fun folderKey(id: String) = "folder/$id"
    private fun isFolderKey(key: String?) = key?.startsWith("folder/") == true || key?.startsWith("folder::") == true
    private fun folderIdFromKey(key: String) = key.removePrefix("folder/").removePrefix("folder::")

    private fun folderType(id: String) = folderTypes[id] ?: "normal"
    private fun isSmallFolder(id: String) = folderType(id) in listOf("normal", "vertical", "horizontal", "square")
    private fun folderColumns(id: String) = when (folderType(id)) {
        "vertical" -> 1
        "horizontal" -> 3
        "big" -> 4
        "superbig" -> 5
        else -> 2
    }

    private fun buildFolderEntry(id: String): AppEntry {
        val name = folderNames[id] ?: "Папка"
        val icon = getFolderPreviewIcon(id)
        return AppEntry(name, "folder", id, icon)
    }

    private fun getFolderPreviewIcon(id: String): android.graphics.drawable.Drawable {
        val size = dp(56)
        val bmp = android.graphics.Bitmap.createBitmap(size, size, android.graphics.Bitmap.Config.ARGB_8888)
        val c = android.graphics.Canvas(bmp)
        val p = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
        p.color = 0x33FFFFFF
        val r = dp(14).toFloat()
        c.drawRoundRect(0f,0f,size.toFloat(),size.toFloat(),r,r,p)
        val keys = folders[id].orEmpty()
        val inner = dp(18)
        val pad = dp(8)
        val gap = dp(4)
        for (i in 0 until minOf(4, keys.size)) {
            val row = i / 2; val col = i % 2
            val left = pad + col * (inner + gap)
            val top = pad + row * (inner + gap)
            val app = findApp(keys[i])
            val d = app?.icon ?: android.graphics.drawable.ColorDrawable(0x55FFFFFF)
            d.setBounds(left, top, left + inner, top + inner)
            d.draw(c)
        }
        return android.graphics.drawable.BitmapDrawable(resources, bmp)
    }

    private fun openFolder(id: String) {
        openFolderId = id; folderPage = 0
        renderFolder(id, animate = true)
    }

    private fun folderPageSize(id: String): Int {
        val cols = folderColumns(id).coerceIn(2, 5)
        val rows = when (folderType(id)) { "big" -> 4; "superbig" -> 5; else -> 3 }
        return (cols * rows).coerceAtLeast(4)
    }

    private fun renderFolder(id: String, animate: Boolean = false) {
        val overlay = findViewById<FrameLayout>(R.id.folderOverlay)
        val card = findViewById<LinearLayout>(R.id.folderCard)
        val title = findViewById<TextView>(R.id.folderTitle)
        val rv = findViewById<RecyclerView>(R.id.folderRecycler)
        val dots = findViewById<LinearLayout?>(R.id.folderPageIndicator)
        card.background = roundedBg(0xAA1C1C1E.toInt())
        title.text = folderNames[id] ?: "Папка"
        title.setOnClickListener { renameFolderDialog(id) }
        val all = folders[id].orEmpty().mapNotNull { findApp(it) }
        val cols = folderColumns(id).coerceIn(2, 5)
        val pageSize = folderPageSize(id)
        val pageCount = maxOf(1, (all.size + pageSize - 1) / pageSize)
        folderPage = folderPage.coerceIn(0, pageCount - 1)
        val pageItems = all.drop(folderPage * pageSize).take(pageSize)
        rv.layoutManager = GridLayoutManager(this, cols)
        val ad = (rv.adapter as? DrawerAdapter) ?: DrawerAdapter(
            onClick = { app, icon ->
                lastLaunchKey = folderKey(id)
                closeFolderOverlay()
                launchApp(app, icon, fromDrawer = false)
            },
            onLongPress = { app, view -> showIconMenu(app, view, fromDock = null, fromDrawer = true); true },
            shape = { prefs.iconShape },
            cornerRadii = { currentCornerRadii() }
        ).also { rv.adapter = it }
        ad.submit(pageItems)
        val itemW = dp(72); val itemH = dp(92)
        rv.layoutParams = rv.layoutParams.apply { width = cols * itemW; height = LinearLayout.LayoutParams.WRAP_CONTENT }
        card.minimumWidth = cols * itemW + dp(32)
        dots?.removeAllViews()
        dots?.isVisible = pageCount > 1
        if (pageCount > 1) {
            for (i in 0 until pageCount) {
                val d = View(this)
                val sz = dp(if (i == folderPage) 7 else 5)
                val lp = LinearLayout.LayoutParams(sz, sz); lp.marginStart = dp(3); lp.marginEnd = dp(3)
                d.layoutParams = lp
                d.background = android.graphics.drawable.GradientDrawable().apply { shape = android.graphics.drawable.GradientDrawable.OVAL; setColor(if (i == folderPage) 0xFFFFFFFF.toInt() else 0x66FFFFFF) }
                dots?.addView(d)
            }
        }
        val resize = findViewById<TextView>(R.id.folderResizeHandle)
        resize.isVisible = true
        resize.setOnClickListener { showFolderTypePicker(id) }
        if (!overlay.isVisible) {
            overlay.isVisible = true
            overlay.alpha = 0f
            card.scaleX = 0.92f; card.scaleY = 0.92f
            overlay.animate().alpha(1f).setDuration(180).start()
            card.animate().scaleX(if (animate) 1f else 1f).scaleY(1f).setDuration(if (animate) 260 else 0).setInterpolator(DecelerateInterpolator(1.8f)).start()
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) { val r = (prefs.drawerBlur.coerceIn(0,100) / 100f * 30f).coerceAtLeast(1f); val be = RenderEffect.createBlurEffect(r,r, Shader.TileMode.CLAMP); homeContent.setRenderEffect(be); wallpaperBlurView?.setRenderEffect(be) }
        overlay.setOnClickListener { closeFolderOverlay() }
        card.setOnClickListener { }
        card.setOnTouchListener { _, ev ->
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> { card.tag = ev.x; false }
                MotionEvent.ACTION_UP -> {
                    val dx = ev.x - ((card.tag as? Float) ?: ev.x)
                    if (dx < -dp(40) && folderPage < pageCount - 1) { folderPage++; renderFolder(id); true }
                    else if (dx > dp(40) && folderPage > 0) { folderPage--; renderFolder(id); true }
                    else false
                }
                else -> false
            }
        }
    }

    private fun closeFolderOverlay() {
        openFolderId = null
        val overlay = findViewById<FrameLayout>(R.id.folderOverlay)
        val card = findViewById<LinearLayout?>(R.id.folderCard)
        card?.animate()?.scaleX(0.94f)?.scaleY(0.94f)?.setDuration(120)?.start()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) { homeContent.setRenderEffect(null); wallpaperBlurView?.setRenderEffect(null) }
        if (appDrawer.isVisible) applyBlur(true)
        overlay.animate().alpha(0f).setDuration(150).withEndAction { overlay.isVisible = false; overlay.alpha = 1f; card?.scaleX = 1f; card?.scaleY = 1f }.start()
    }

    private fun renameFolderDialog(id: String) {
        val input = android.widget.EditText(this)
        input.setText(folderNames[id] ?: "Папка")
        AlertDialog.Builder(this).setTitle("Имя папки").setView(input)
            .setPositiveButton("OK") { _, _ ->
                folderNames[id] = input.text?.toString()?.trim().takeUnless { it.isNullOrBlank() } ?: "Папка"
                saveFoldersToPrefs(); refreshFolderIcons(); openFolder(id)
            }.setNegativeButton("Отмена", null).show()
    }

    private fun refreshFolderIcons() {
        for (p in pages.indices) {
            for (i in pages[p].indices) {
                val e = pages[p][i] ?: continue
                if (isFolderKey(e.key)) pages[p][i] = buildFolderEntry(folderIdFromKey(e.key))
            }
        }
        if (currentPageIndex in pages.indices) gridAdapter.submitApps(pages[currentPageIndex])
        persistLayout()
    }

    private fun showFolderTypePicker(id: String) {
        val opts = listOf(
            "normal" to "Обычная (квадратная)",
            "vertical" to "Вертикальная",
            "horizontal" to "Горизонтальная",
            "big" to "Большая",
            "superbig" to "Супербольшая"
        )
        val labels = opts.map { (k, v) -> if (folderType(id) == k) "✓ $v" else v }.toTypedArray()
        AlertDialog.Builder(this).setTitle("Размер папки")
            .setItems(labels) { _, which ->
                folderTypes[id] = opts[which].first
                saveFoldersToPrefs()
                refreshFolderIcons()
            }.show()
    }

    private fun createFolderWith(appKey1: String, appKey2: String, targetSlot: Int) {
        val id = "f${System.currentTimeMillis()}"
        folderNames[id] = "Папка"
        folderTypes[id] = "normal"
        folders[id] = mutableListOf(appKey1, appKey2)
        saveFoldersToPrefs()
        val folderEntry = buildFolderEntry(id)
        // place folder in targetSlot on current page
        val slots = gridAdapter.allSlots().toMutableList()
        // remove both apps from slots
        for (i in slots.indices) { if (slots[i]?.key == appKey1 || slots[i]?.key == appKey2) slots[i] = null }
        slots[targetSlot.coerceIn(0, pageSize-1)] = folderEntry
        pages[currentPageIndex] = slots
        gridAdapter.submitApps(slots)
        persistLayout()
    }

    private var dockFolderPopup: View? = null
    private fun showDockFolderPopup(anchor: View) {
        try {
            dockFolderPopup?.let { try { root.removeView(it) } catch (_: Exception) {} }
            val wrap = FrameLayout(this)
            wrap.layoutParams = FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
            wrap.isClickable = true; wrap.isFocusable = true
            val dim = View(this)
            dim.layoutParams = FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
            dim.setBackgroundColor(0x55000000)
            dim.alpha = 0f
            dim.setOnClickListener {
                val p = wrap.findViewWithTag<View>("dockPanel")
                if (p != null) {
                    p.animate().scaleX(0.7f).scaleY(0.7f).alpha(0f).translationY(dp(60).toFloat()).setDuration(180).withEndAction {
                        try { root.removeView(wrap) } catch (_: Exception) {}; dockFolderPopup = null
                    }.start()
                    dim.animate().alpha(0f).setDuration(180).start()
                } else { try { root.removeView(wrap) } catch (_: Exception) {}; dockFolderPopup = null }
            }
            wrap.addView(dim)

            val panel = LinearLayout(this)
            panel.tag = "dockPanel"
            panel.orientation = LinearLayout.VERTICAL
            panel.background = roundedBg(0xEE1F1F1F.toInt())
            panel.setPadding(dp(16), dp(14), dp(16), dp(16))
            val pLp = FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT)
            pLp.gravity = android.view.Gravity.BOTTOM
            pLp.bottomMargin = dp(110)
            pLp.marginStart = dp(12); pLp.marginEnd = dp(12)
            panel.layoutParams = pLp
            panel.elevation = dp(12).toFloat()
            panel.clipToOutline = true

            val title = TextView(this)
            title.text = "Библиотека приложений"
            title.setTextColor(0xFFFFFFFF.toInt()); title.textSize = 17f; title.typeface = android.graphics.Typeface.DEFAULT_BOLD
            panel.addView(title)

            val grid = RecyclerView(this)
            grid.layoutManager = GridLayoutManager(this, 4)
            val ad = DrawerAdapter(
                onClick = { app, icon ->
                    try { root.removeView(wrap) } catch (_: Exception) {}; dockFolderPopup = null
                    launchApp(app, icon, fromDrawer = true)
                },
                onLongPress = { app, view -> showIconMenu(app, view, fromDock = null, fromDrawer = true); true },
                shape = { prefs.iconShape }, cornerRadii = { currentCornerRadii() }
            )
            grid.adapter = ad
            ad.submit(allApps)
            val gLp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(340))
            gLp.topMargin = dp(12)
            grid.layoutParams = gLp
            panel.addView(grid)

            wrap.addView(panel)
            root.addView(wrap)
            dockFolderPopup = wrap

            // Animate from dock anchor
            val loc = IntArray(2); anchor.getLocationInWindow(loc)
            val ax = loc[0] + anchor.width / 2f
            val screenW = resources.displayMetrics.widthPixels.toFloat()
            panel.pivotX = ax.coerceIn(dp(40).toFloat(), screenW - dp(40).toFloat())
            panel.pivotY = dp(360).toFloat()
            panel.scaleX = 0.25f; panel.scaleY = 0.25f
            panel.translationY = dp(120).toFloat()
            panel.alpha = 0f
            dim.animate().alpha(1f).setDuration(220).start()
            panel.animate().scaleX(1f).scaleY(1f).translationY(0f).alpha(1f)
                .setDuration(320).setInterpolator(DecelerateInterpolator(1.5f)).start()
        } catch (_: Exception) { }
    }

    private fun getDockFolderIcon(): android.graphics.drawable.Drawable {
        val size = dp(56)
        val bmp = android.graphics.Bitmap.createBitmap(size, size, android.graphics.Bitmap.Config.ARGB_8888)
        val c = android.graphics.Canvas(bmp)
        val p = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
        p.color = 0x33FFFFFF
        val r = dp(14).toFloat()
        c.drawRoundRect(0f,0f,size.toFloat(),size.toFloat(),r,r,p)
        p.color = 0xCCFFFFFF.toInt()
        val dot = dp(6).toFloat()
        val gap = dp(14).toFloat()
        val start = dp(14).toFloat()
        for (row in 0..1) for (col in 0..1) {
            c.drawRoundRect(start + col*gap, start + row*gap, start + col*gap + dot, start + row*gap + dot, dp(2).toFloat(), dp(2).toFloat(), p)
        }
        return android.graphics.drawable.BitmapDrawable(resources, bmp)
    }

    private fun showIconMenu(app: AppEntry, view: View, fromDock: Int?, fromDrawer: Boolean): Boolean {
        val overlay = findViewById<FrameLayout>(R.id.iconMenuOverlay)
        val card = findViewById<LinearLayout>(R.id.iconMenuCard)
        val bg = findViewById<View>(R.id.iconMenuBg)
        val preview = findViewById<ImageView>(R.id.iconMenuPreview)
        card.removeAllViews()
        card.setOnClickListener { }
        card.background = roundedBg(0xF0222222.toInt())
        card.elevation = dp(12).toFloat()

        fun closeMenu() {
            overlay.isVisible = false
            preview.isVisible = false
            clearBlur()
            if (appDrawer.isVisible) appDrawer.alpha = 1f
        }
        bg.setOnClickListener { closeMenu() }
        overlay.setOnClickListener { closeMenu() }

        val title = TextView(this).apply {
            text = app.label
            setTextColor(0xFFFFFFFF.toInt())
            textSize = 14f
            setPadding(dp(12), dp(10), dp(12), dp(6))
            typeface = android.graphics.Typeface.DEFAULT_BOLD
        }
        card.addView(title)

        fun addOption(label: String, danger: Boolean = false, action: () -> Unit) {
            val tv = TextView(this).apply {
                text = label
                setTextColor(if (danger) 0xFFFF6B6B.toInt() else 0xFFFFFFFF.toInt())
                textSize = 14f
                setPadding(dp(12), dp(12), dp(12), dp(12))
                isClickable = true; isFocusable = true
                setOnClickListener { closeMenu(); action() }
            }
            card.addView(tv)
        }

        if (isFolderKey(app.key)) {
            val fid = folderIdFromKey(app.key)
            addOption("Открыть") { openFolder(fid) }
            addOption("Переименовать") { renameFolderDialog(fid) }
            addOption("Размер / вид папки") { showFolderTypePicker(fid) }
            addOption("На страницу...") { showPageManager(app.key) }
            addOption("Удалить папку", danger = true) {
                val keys = folders[fid].orEmpty()
                folders.remove(fid); folderNames.remove(fid); folderTypes.remove(fid); saveFoldersToPrefs()
                gridAdapter.removeByKey(app.key)
                keys.forEach { k -> findApp(k)?.let { placeAppInSlot(it, currentPageIndex, firstEmptySlot(currentPageIndex)) } }
                persistLayout(); renderCurrentPage(); renderDock()
            }
        } else {
            addOption("Переместить") {
                view.setOnTouchListener { v, ev ->
                    if (ev.action == android.view.MotionEvent.ACTION_DOWN) {
                        v.setOnTouchListener(null)
                        startIconDrag(app, v, fromDock, fromDrawer)
                        true
                    } else false
                }
                android.widget.Toast.makeText(this, "Перетащите иконку", android.widget.Toast.LENGTH_SHORT).show()
            }
            addOption("На страницу...") { showPageManager(app.key) }
            addOption("Изменить иконку") { showIconEditDialog(app) }
            addOption("Настроить анимацию открытия") { showPerAppAnimEditor(app) }
            if (!fromDrawer) {
                addOption("Убрать с рабочего стола") {
                    animateShatter(view) {
                        if (fromDock != null) dockApps[fromDock] = null else gridAdapter.removeByKey(app.key)
                        renderDock(); persistLayout(); updatePageIndicator()
                    }
                }
            }
            addOption("Удалить с телефона", danger = true) {
                try {
                    val uri = android.net.Uri.parse("package:${app.packageName}")
                    val i = Intent(Intent.ACTION_DELETE, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    startActivity(i)
                } catch (e: Exception) {
                    try { startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, android.net.Uri.parse("package:${app.packageName}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } catch (_: Exception) { }
                }
            }
        }

        // position
        val loc = IntArray(2); view.getLocationOnScreen(loc)
        val iconSize = view.width.coerceAtLeast(dp(48))
        preview.setImageDrawable(app.icon.constantState?.newDrawable()?.mutate() ?: app.icon)
        preview.layoutParams = FrameLayout.LayoutParams(iconSize, iconSize).apply { leftMargin = loc[0]; topMargin = loc[1] }
        preview.isVisible = true
        AppGridAdapter.applyShape(preview, prefs.iconShape, currentCornerRadii())

        overlay.isVisible = true
        overlay.bringToFront()
        card.post {
            val lp = card.layoutParams as FrameLayout.LayoutParams
            val screenW = root.width
            val screenH = root.height
            var left = loc[0]
            var top = loc[1] + iconSize + dp(8)
            if (left + card.width > screenW - dp(12)) left = screenW - card.width - dp(12)
            if (left < dp(12)) left = dp(12)
            if (top + card.height > screenH - dp(12)) top = loc[1] - card.height - dp(8)
            if (top < dp(12)) top = dp(12)
            lp.leftMargin = left; lp.topMargin = top
            card.layoutParams = lp
        }

        if (prefs.blurEnabled && !prefs.powerSaving && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val be = RenderEffect.createBlurEffect(30f,30f, Shader.TileMode.CLAMP); homeContent.setRenderEffect(be); wallpaperBlurView?.setRenderEffect(be)
            if (appDrawer.isVisible) appDrawer.alpha = 0.7f
        }
        return true
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun startIconDrag(app: AppEntry, view: View, fromDock: Int?, fromDrawer: Boolean): Boolean {
        draggedKey = app.key
        draggedFromDock = fromDock
        draggedFromDrawer = fromDrawer
        draggedFromPage = if (fromDock == null && !fromDrawer) currentPageIndex else -1
        dragDropped = false
        quickPanel.isVisible = false; settingsButton.isVisible = false
        val data = ClipData.newPlainText("app", app.key)
        val shadow = View.DragShadowBuilder(view)
        return try {
            view.startDragAndDrop(data, shadow, app.key, 0)
            view.alpha = 0.35f
            if (fromDrawer) {
                appDrawer.animate().cancel()
                appDrawer.translationY = appDrawer.height.toFloat().takeIf { it > 0f } ?: 2000f
                appDrawer.alpha = 0f
            }
            true
        } catch (_: Exception) {
            draggedKey = null; draggedFromDock = null; draggedFromDrawer = false; draggedFromPage = -1
            false
        }
    }

    private fun gridDropIndex(x: Float, y: Float): Int {
        val child = gridRecycler.findChildViewUnder(x, y)
        if (child != null) return gridRecycler.getChildAdapterPosition(child).coerceIn(0, pageSize - 1)
        val cols = (gridRecycler.layoutManager as? GridLayoutManager)?.spanCount ?: 4
        val padTop = gridRecycler.paddingTop.toFloat()
        val usableW = (gridRecycler.width - gridRecycler.paddingLeft - gridRecycler.paddingRight).toFloat().coerceAtLeast(1f)
        val cellW = usableW / cols
        val first = gridRecycler.getChildAt(0)
        val cellH = (first?.height?.toFloat() ?: (100f * resources.displayMetrics.density)).coerceAtLeast(1f)
        val col = ((x - gridRecycler.paddingLeft) / cellW).toInt().coerceIn(0, cols - 1)
        val row = ((y - padTop + gridRecycler.computeVerticalScrollOffset()) / cellH).toInt().coerceAtLeast(0)
        return (row * cols + col).coerceIn(0, pageSize - 1)
    }

    private fun handleGridDrag(event: android.view.DragEvent): Boolean {
        when (event.action) {
            android.view.DragEvent.ACTION_DRAG_STARTED -> showDragGrid()
            android.view.DragEvent.ACTION_DRAG_LOCATION -> {
                val edge = 56f * resources.displayMetrics.density
                val w = gridRecycler.width.toFloat()
                when {
                    event.x < edge -> schedulePageSwitch(-1)
                    event.x > w - edge -> schedulePageSwitch(1)
                    else -> cancelPageSwitch()
                }
            }
            android.view.DragEvent.ACTION_DRAG_EXITED -> cancelPageSwitch()
            android.view.DragEvent.ACTION_DRAG_ENDED -> { cancelPageSwitch(); resetDragState() }
            android.view.DragEvent.ACTION_DROP -> try {
                cancelPageSwitch()
                val key = event.clipData?.getItemAt(0)?.text?.toString() ?: return true
                val insertAt = gridDropIndex(event.x, event.y)
                pendingDropKey = key
                pendingDropDockSlot = null
                pendingDropGridIndex = insertAt
                dragDropped = true
            } catch (_: Exception) { }
        }
        return true
    }

    private fun schedulePageSwitch(dir: Int) {
        if (pageSwitchRunnable != null) return
        pageSwitchRunnable = Runnable {
            pageSwitchRunnable = null
            switchPage(dir, fromDrag = true)
        }
        root.postDelayed(pageSwitchRunnable!!, 650)
    }

    private fun cancelPageSwitch() {
        pageSwitchRunnable?.let { root.removeCallbacks(it) }
        pageSwitchRunnable = null
    }

    private fun handleDockDrag(event: android.view.DragEvent): Boolean {
        when (event.action) {
            android.view.DragEvent.ACTION_DRAG_ENTERED -> dockBar.setBackgroundResource(R.drawable.bg_dock_highlight)
            android.view.DragEvent.ACTION_DRAG_EXITED, android.view.DragEvent.ACTION_DRAG_ENDED -> dockBar.setBackgroundResource(R.drawable.bg_dock)
        }
        if (event.action == android.view.DragEvent.ACTION_DRAG_ENDED) resetDragState()
        return true
    }

    private fun handleDockSlotDrag(slot: Int, event: android.view.DragEvent): Boolean {
        val slotView = dockBar.getChildAt(slot)
        when (event.action) {
            android.view.DragEvent.ACTION_DRAG_STARTED -> {
                showDragGrid()
                slotView?.foreground = android.graphics.drawable.GradientDrawable().apply {
                    cornerRadius = 18f * resources.displayMetrics.density
                    setStroke((2 * resources.displayMetrics.density).toInt(), 0x55FFFFFF)
                }
            }
            android.view.DragEvent.ACTION_DRAG_ENTERED -> {
                dockBar.setBackgroundResource(R.drawable.bg_dock_highlight)
                slotView?.foreground = android.graphics.drawable.GradientDrawable().apply {
                    cornerRadius = 18f * resources.displayMetrics.density
                    setStroke((2 * resources.displayMetrics.density).toInt(), prefs.accentColor)
                    setColor(0x33FFFFFF)
                }
            }
            android.view.DragEvent.ACTION_DRAG_EXITED -> {
                dockBar.setBackgroundResource(R.drawable.bg_dock)
                slotView?.foreground = android.graphics.drawable.GradientDrawable().apply {
                    cornerRadius = 18f * resources.displayMetrics.density
                    setStroke((2 * resources.displayMetrics.density).toInt(), 0x55FFFFFF)
                }
            }
            android.view.DragEvent.ACTION_DROP -> try {
                val key = event.clipData?.getItemAt(0)?.text?.toString() ?: return true
                pendingDropKey = key
                pendingDropDockSlot = slot
                pendingDropGridIndex = -1
                dragDropped = true
            } catch (_: Exception) { }
            android.view.DragEvent.ACTION_DRAG_ENDED -> {
                slotView?.foreground = null
                resetDragState()
            }
        }
        return true
    }

    private fun placeInDockOrDrop(app: AppEntry) {
        val empty = dockApps.indexOfFirst { it == null }
        if (empty >= 0) dockApps[empty] = app
    }

    private fun findApp(key: String): AppEntry? = allApps.firstOrNull { it.key == key }

    private fun removeAppFromPages(key: String, exceptPage: Int = -1) {
        pages.forEachIndexed { i, p ->
            if (i != exceptPage) {
                val idx = p.indexOfFirst { it?.key == key }
                if (idx >= 0) p[idx] = null
            }
        }
    }

    private fun applyPendingDropNow(key: String, toDock: Int?, toGrid: Int, fromDock: Int?, fromDrawer: Boolean, fromPage: Int) {
        val app = findApp(key) ?: return
        ensurePages()
        val targetSlot = toGrid.coerceIn(0, pageSize - 1)

        if (toDock != null) {
            if (fromDock == toDock) return
            val replaced = dockApps[toDock]
            // remove from source
            if (fromDock != null) {
                dockApps[fromDock] = null
            } else if (!fromDrawer) {
                removeFromPageSlot(key, fromPage)
            }
            // place replaced back to source grid slot if possible
            if (replaced != null && replaced.key != app.key) {
                if (fromDock != null) {
                    dockApps[fromDock] = replaced
                } else if (!fromDrawer) {
                    placeAppInSlot(replaced, fromPage, if (fromPage == currentPageIndex) targetSlot else 0)
                } else {
                    placeAppInSlot(replaced, currentPageIndex, firstEmptySlot(currentPageIndex))
                }
            }
            dockApps[toDock] = app
        } else {
            // drop to grid slot
            val currentSlots = gridAdapter.allSlots().toMutableList()
            // remove app from source
            var sourcePage = fromPage
            var sourceSlot = -1
            if (fromDock != null) {
                dockApps[fromDock] = null
            } else if (!fromDrawer) {
                if (fromPage >= 0 && fromPage < pages.size && fromPage != currentPageIndex) {
                    sourceSlot = pages[fromPage].indexOfFirst { it?.key == key }
                    if (sourceSlot >= 0) pages[fromPage][sourceSlot] = null
                } else {
                    sourceSlot = currentSlots.indexOfFirst { it?.key == key }
                    if (sourceSlot >= 0) currentSlots[sourceSlot] = null
                }
            }
            // also clear duplicates on other pages
            removeAppFromPages(key, exceptPage = currentPageIndex)
            // occupy target
            val occupant = currentSlots[targetSlot]
            if (occupant == null || occupant.key == key) {
                currentSlots[targetSlot] = app
            } else if (isFolderKey(occupant.key) && !isFolderKey(app.key)) {
                val fid = folderIdFromKey(occupant.key)
                val list = folders.getOrPut(fid) { mutableListOf() }
                if (app.key !in list) list.add(app.key)
                folderNames.putIfAbsent(fid, "Папка")
                saveFoldersToPrefs()
                currentSlots[targetSlot] = buildFolderEntry(fid)
            } else if (!isFolderKey(occupant.key) && !isFolderKey(app.key)) {
                val id = "f${System.currentTimeMillis()}"
                folderNames[id] = "Папка"
                folderTypes[id] = "normal"
                folders[id] = mutableListOf(occupant.key, app.key)
                saveFoldersToPrefs()
                currentSlots[targetSlot] = buildFolderEntry(id)
            } else {
                // swap
                currentSlots[targetSlot] = app
                if (!fromDrawer && fromDock == null && sourcePage == currentPageIndex && sourceSlot >= 0 && sourceSlot != targetSlot) {
                    currentSlots[sourceSlot] = occupant
                } else if (!fromDrawer && fromDock == null && sourcePage >= 0 && sourcePage != currentPageIndex && sourceSlot >= 0) {
                    pages[sourcePage][sourceSlot] = occupant
                } else {
                    val empty = currentSlots.indexOfFirst { it == null }
                    if (empty >= 0) currentSlots[empty] = occupant
                    else placeOverflowOnNextPage(occupant)
                }
            }
            pages[currentPageIndex] = currentSlots
            gridAdapter.submitApps(currentSlots)
        }
    }

    private fun removeFromPageSlot(key: String, page: Int) {
        if (page >= 0 && page < pages.size) {
            val idx = pages[page].indexOfFirst { it?.key == key }
            if (idx >= 0) pages[page][idx] = null
            if (page == currentPageIndex) gridAdapter.removeByKey(key)
        } else {
            gridAdapter.removeByKey(key)
        }
    }

    private fun placeAppInSlot(app: AppEntry, page: Int, preferred: Int) {
        ensurePages()
        if (page < 0 || page >= pages.size) return
        removeAppFromPages(app.key, exceptPage = -1)
        val p = pages[page]
        val slot = if (preferred in 0 until pageSize && p[preferred] == null) preferred else p.indexOfFirst { it == null }
        if (slot >= 0) p[slot] = app else placeOverflowOnNextPage(app)
        if (page == currentPageIndex) gridAdapter.submitApps(pages[page])
    }

    private fun firstEmptySlot(page: Int): Int {
        if (page < 0 || page >= pages.size) return 0
        return pages[page].indexOfFirst { it == null }.let { if (it >= 0) it else 0 }
    }

    private fun placeOverflowOnNextPage(app: AppEntry) {
        var target = currentPageIndex + 1
        if (target >= pages.size) pages.add(newPage())
        removeAppFromPages(app.key, exceptPage = -1)
        val slot = pages[target].indexOfFirst { it == null }
        if (slot >= 0) pages[target][slot] = app else {
            if (target + 1 >= pages.size) pages.add(newPage())
            val s2 = pages[target + 1].indexOfFirst { it == null }
            if (s2 >= 0) pages[target + 1][s2] = app
        }
    }

    private fun showDragGrid() {
        try {
            val host = (gridRecycler.parent as? ViewGroup) ?: findViewById<ViewGroup>(R.id.workspaceContainer)
            if (dragGridOverlay == null) {
                dragGridOverlay = DragGridOverlay(this).apply {
                    layoutParams = FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
                    isClickable = false; isFocusable = false
                }
                host.addView(dragGridOverlay)
            } else if (dragGridOverlay?.parent !== host) {
                (dragGridOverlay?.parent as? ViewGroup)?.removeView(dragGridOverlay)
                host.addView(dragGridOverlay)
            }
            val cols = (gridRecycler.layoutManager as? GridLayoutManager)?.spanCount ?: 4
            dragGridOverlay?.cols = cols
            dragGridOverlay?.rows = ((pageSize + cols - 1) / cols).coerceAtLeast(1)
            dragGridOverlay?.visibility = View.VISIBLE
            dragGridOverlay?.bringToFront()
            gridRecycler.bringToFront()
            findViewById<View>(R.id.pageEdgeLeft).bringToFront()
            findViewById<View>(R.id.pageEdgeRight).bringToFront()
            dragGridOverlay?.invalidate()
        } catch (_: Exception) { }
    }

    private fun hideDragGrid() {
        try { dragGridOverlay?.visibility = View.GONE } catch (_: Exception) { }
    }

    private fun ensureAppOnHome(key: String, fromDock: Int?, preferredPage: Int) {
        val app = findApp(key) ?: return
        val onHome = gridAdapter.allSlots().any { it?.key == key } || pages.any { p -> p.any { it?.key == key } } || dockApps.any { it?.key == key }
        if (onHome) return
        if (fromDock != null) {
            if (dockApps[fromDock] == null) { dockApps[fromDock] = app; return }
            val emptyDock = dockApps.indexOfFirst { it == null }
            if (emptyDock >= 0) { dockApps[emptyDock] = app; return }
        }
        ensurePages()
        val targetPage = preferredPage.coerceIn(0, pages.lastIndex)
        fun placeOn(pageIdx: Int): Int {
            val p = pages[pageIdx]
            val empty = p.indexOfFirst { it == null }
            if (empty >= 0) { p[empty] = app; if (pageIdx == currentPageIndex) gridAdapter.submitApps(p); return empty }
            return -1
        }
        var slot = placeOn(targetPage)
        if (slot < 0) {
            for (i in pages.indices) { slot = placeOn(i); if (slot >= 0) { if (i == currentPageIndex) gridAdapter.submitApps(pages[i]); break } }
        }
        if (slot < 0) {
            pages.add(newPage())
            val idx = pages.lastIndex
            pages[idx][0] = app
        }
        if (targetPage == currentPageIndex) renderCurrentPage()
    }

    private fun resetDragState() {
        if (draggedKey == null && !draggedFromDrawer) {
            for (i in 0 until gridRecycler.childCount) gridRecycler.getChildAt(i)?.alpha = 1f
            for (i in 0 until dockBar.childCount) { dockBar.getChildAt(i)?.alpha = 1f; dockBar.getChildAt(i)?.foreground = null }
            return
        }
        val dropped = dragDropped
        val fromDrawer = draggedFromDrawer
        val key = draggedKey
        val pKey = pendingDropKey
        val pDock = pendingDropDockSlot
        val pGrid = pendingDropGridIndex
        val fromDock = draggedFromDock
        val fromPage = draggedFromPage

        draggedKey = null; draggedFromDock = null; draggedFromDrawer = false; draggedFromPage = -1; dragDropped = false
        pendingDropKey = null; pendingDropDockSlot = null; pendingDropGridIndex = -1
        iosJiggleMode = false
        try { val g = findViewById<LinearLayout?>(R.id.iosHomeLibraryGrid); if (g != null) for (i in 0 until g.childCount) g.getChildAt(i)?.clearAnimation() } catch (_: Exception) {}

        root.post {
            try {
                var placed = false
                if (dropped && pKey != null) {
                    applyPendingDropNow(pKey, pDock, pGrid, fromDock, fromDrawer, fromPage)
                    placed = true
                }
                if (!placed && key != null && !fromDrawer) {
                    ensureAppOnHome(key, fromDock, if (fromPage >= 0) fromPage else currentPageIndex)
                    placed = true
                }
                dockBar.setBackgroundResource(R.drawable.bg_dock)
                for (i in 0 until gridRecycler.childCount) gridRecycler.getChildAt(i)?.alpha = 1f
                for (i in 0 until dockBar.childCount) { dockBar.getChildAt(i)?.alpha = 1f; dockBar.getChildAt(i)?.foreground = null }
                if (fromDrawer && key != null) {
                    val onHome = gridAdapter.all().any { it.key == key } || dockApps.filterNotNull().any { it.key == key }
                    if (!onHome) {
                        appDrawer.isVisible = true
                        appDrawer.alpha = 1f
                        appDrawer.translationY = 0f
                    } else {
                        appDrawer.isVisible = false
                        appDrawer.alpha = 1f
                        appDrawer.translationY = 0f
                    }
                }
                renderDock()
                if (placed) persistLayout()
                hideDragGrid()
            } catch (_: Exception) { }
        }
    }

    private fun layoutMode(): String {
        val landscape = resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE
        return when { !landscape -> "portrait"; prefs.desktopMode -> "desktop"; else -> "landscape" }
    }

    private fun persistLayout() { persistLayoutForMode(layoutMode()) }

    private fun persistLayoutForMode(mode: String) {
        ensurePages()
        pages[currentPageIndex] = gridAdapter.allSlots().toMutableList()
        while (pages.size > 1 && pages.last().all { it == null }) pages.removeAt(pages.lastIndex)
        val clamped = currentPageIndex.coerceIn(0, pages.lastIndex)
        val changed = clamped != currentPageIndex
        currentPageIndex = clamped
        prefs.savePages(pages.map { p -> p.map { it?.key } }, mode)
        prefs.currentPage = currentPageIndex
        prefs.saveDockOrder(dockApps.mapNotNull { it?.key }, mode)
        if (changed) renderCurrentPage() else updatePageIndicator()
    }

    private fun loadLayoutForMode(mode: String) {
        val byKey = allApps.associateBy { it.key }
        val savedDock = prefs.loadDockOrder(mode).mapNotNull { byKey[it] }.distinctBy { it.key }.take(4)
        for (i in 0 until 4) dockApps[i] = savedDock.getOrNull(i)
        val used = dockApps.filterNotNull().map { it.key }.toMutableSet()
        val savedPages = prefs.loadPages(mode)
        pages.clear()
        if (savedPages.isNotEmpty()) {
            savedPages.forEach { pageKeys ->
                val page = MutableList<AppEntry?>(pageSize) { null }
                for (i in 0 until pageSize) {
                    val k = pageKeys.getOrNull(i)
                    val app = when {
                        k == null -> null
                        isFolderKey(k) -> buildFolderEntry(folderIdFromKey(k))
                        else -> byKey[k]
                    }
                    if (app != null && app.key !in used) { page[i] = app; used.add(app.key) }
                }
                pages.add(page)
            }
        }
        if (pages.isEmpty()) pages.add(newPage())
        while (pages.size > 1 && pages.last().all { it == null }) pages.removeAt(pages.lastIndex)
        currentPageIndex = prefs.currentPage.coerceIn(0, pages.lastIndex)
        renderCurrentPage()
        renderDock()
    }

    private fun launchApp(app: AppEntry, iconView: View, fromDrawer: Boolean) {
        loadAppAnimIfAny(app.key)
        val intent = Intent(Intent.ACTION_MAIN)
            .addCategory(Intent.CATEGORY_LAUNCHER)
            .setComponent(ComponentName(app.packageName, app.activityName))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

        val doLaunch = {
            val loc = IntArray(2)
            iconView.getLocationOnScreen(loc)
            lastLaunchKey = app.key
            lastLaunchBounds = intArrayOf(loc[0], loc[1], iconView.width, iconView.height)
            lastLaunchColor = extractColor(app.icon)

            if (!prefs.flagshipAnimations) {
                playSimpleOpenAnimation {
                    val opts = ActivityOptions.makeCustomAnimation(this, 0, 0)
                    try { startActivity(intent, opts.toBundle()); pendingLaunch = true } catch (_: Exception) { pendingLaunch = false; animationOverlay.isVisible = false }
                }
            } else {
                playOpenAnimation(fromDrawer, lastLaunchBounds!!, lastLaunchColor) {
                    val opts = ActivityOptions.makeCustomAnimation(this, 0, 0)
                    try {
                        startActivity(intent, opts.toBundle())
                        pendingLaunch = true
                    } catch (_: Exception) {
                        pendingLaunch = false
                        clearBlur()
                        animationOverlay.isVisible = false
                    }
                }
            }
        }

        if (prefs.iconPressAnim) {
            iconView.animate().cancel()
            iconView.animate().scaleX(0.86f).scaleY(0.86f).setDuration(70).withEndAction {
                iconView.animate().scaleX(1f).scaleY(1f).setDuration(90).start()
                doLaunch()
            }.start()
        } else {
            doLaunch()
        }
    }

    private fun playSimpleOpenAnimation(onEnd: () -> Unit) {
        openAnimating = true
        animateDock(false)
        expandOverlayToFullscreen()
        val overlay = animationOverlay
        overlay.animate().cancel()
        overlay.background = roundedBg(prefs.accentColor)
        overlay.clipToOutline = true
        val sr = prefs.windowCornerRadius.coerceIn(0, 60) * resources.displayMetrics.density
        overlay.outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) { outline.setRoundRect(0, 0, view.width, view.height, sr) }
        }
        overlay.invalidateOutline()
        overlay.isVisible = true
        overlay.alpha = 0f
        overlay.scaleX = 0.2f; overlay.scaleY = 0.2f
        overlay.translationX = 0f; overlay.translationY = 0f
        overlay.rotationX = 0f; overlay.rotationY = 0f
        overlay.animate().alpha(1f).scaleX(1f).scaleY(1f)
            .setDuration(280).setInterpolator(DecelerateInterpolator(1.5f))
            .withEndAction { openAnimating = false; onEnd() }.start()
    }

    private fun playOpenAnimation(fromDrawer: Boolean, bounds: IntArray, color: Int, onEnd: () -> Unit) {
        openAnimating = true
        closeAnimator?.cancel(); closeAnimator = null
        closeTargetView?.alpha = 1f; closeTargetView = null
        lastLaunchKey?.let { findIconViewByKey(it)?.alpha = 1f }
        applyBlur(true)
        scaleHomeForOpen(true)
        animateDock(false)
        expandOverlayToFullscreen()

        val overlay = animationOverlay
        overlay.background = roundedBg(color)
        overlay.clipToOutline = true
        val openRadius = prefs.windowCornerRadius.coerceIn(0, 60) * resources.displayMetrics.density
        overlay.outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) { outline.setRoundRect(0, 0, view.width, view.height, openRadius) }
        }
        overlay.invalidateOutline()
        overlay.isVisible = true
        overlay.alpha = 1f

        val screenW = root.width.toFloat().coerceAtLeast(1f)
        val screenH = root.height.toFloat().coerceAtLeast(1f)
        val startW = bounds[2].toFloat().coerceAtLeast(1f)
        val startH = bounds[3].toFloat().coerceAtLeast(1f)
        val startCx = bounds[0] + startW / 2f
        val startCy = bounds[1] + startH / 2f
        val endCx = screenW / 2f
        val endCy = screenH / 2f

        val fromCenter = fromDrawer
        val sX = if (fromCenter) 0.2f else (startW / screenW)
        val sY = if (fromCenter) 0.2f else (startH / screenH)
        val tX = if (fromCenter) 0f else (startCx - endCx)
        val tY = if (fromCenter) 0f else (startCy - endCy)

        overlay.scaleX = sX
        overlay.scaleY = sY
        overlay.translationX = tX
        overlay.translationY = tY
        val newOpen = prefs.newCloseAnim
        if ((prefs.tiltAnimation || newOpen) && !prefs.powerSaving) {
            val ti = prefs.tiltIntensity.coerceIn(0, 100) / 50f * (if (newOpen) 1.3f else 1f)
            overlay.cameraDistance = resources.displayMetrics.density * 7000f
            overlay.pivotX = screenW / 2f
            overlay.pivotY = screenH / 2f
            overlay.rotationX = 14f * ti
            overlay.rotationY = (if (tX < 0) 8f else -8f) * ti
        } else {
            overlay.rotationX = 0f; overlay.rotationY = 0f; overlay.pivotX = screenW / 2f; overlay.pivotY = screenH / 2f
        }

        overlay.animate().cancel()
        if (prefs.animPreset == "genie") {
            overlay.pivotX = overlay.width / 2f
            overlay.pivotY = overlay.height / 2f
            overlay.animate()
                .scaleX(1f).scaleY(1f)
                .translationX(prefs.openTranslateX)
                .translationY(prefs.openTranslateY)
                .rotationX(0f).rotationY(0f)
                .setDuration(prefs.openDuration.toLong().coerceAtLeast(280L))
                .setInterpolator(android.view.animation.OvershootInterpolator(0.7f))
                .withEndAction { if (openAnimating) { openAnimating = false; onEnd() } }
                .start()
        } else {
            overlay.animate()
                .scaleX(1f).scaleY(1f)
                .translationX(prefs.openTranslateX)
                .translationY(prefs.openTranslateY)
                .rotationX(0f).rotationY(0f)
                .setDuration(prefs.openDuration.toLong())
                .setInterpolator(DecelerateInterpolator(1.4f))
                .withEndAction { if (openAnimating) { openAnimating = false; onEnd() } }
                .start()
        }
    }

    private fun playCloseAnimation() {
        val bounds = lastLaunchBounds ?: run { clearBlur(); scaleHomeForOpen(false); openAnimating = false; closeTargetView?.alpha = 1f; closeTargetView = null; return }
        val overlay = animationOverlay
        val wasOpenAnimating = openAnimating
        openAnimating = false
        closeAnimator?.cancel()
        closeAnimator = null
        closeTargetView?.alpha = 1f
        clearBlur()
        expandOverlayToFullscreen()
        overlay.bringToFront()
        animateDock(true)
        if (!prefs.flagshipAnimations || prefs.powerSaving) {
            overlay.animate().cancel()
            overlay.isVisible = true
            overlay.alpha = 1f
            overlay.translationX = 0f; overlay.translationY = 0f
            overlay.scaleX = 1f; overlay.scaleY = 1f
            overlay.rotationX = 0f; overlay.rotationY = 0f
            scaleHomeForOpen(false, 420)
            overlay.animate().alpha(0.08f).scaleX(0.28f).scaleY(0.28f)
                .setDuration(420).setInterpolator(DecelerateInterpolator(1.5f))
                .withEndAction {
                    overlay.isVisible = false; overlay.alpha = 1f; overlay.translationY = 0f; overlay.translationX = 0f; overlay.scaleX = 1f; overlay.scaleY = 1f; overlay.clipToOutline = false; overlay.outlineProvider = ViewOutlineProvider.BACKGROUND
                }.start()
            return
        }

        val screenW = root.width.toFloat().coerceAtLeast(1f)
        val screenH = root.height.toFloat().coerceAtLeast(1f)
        val endW = bounds[2].toFloat().coerceAtLeast(1f)
        val endH = bounds[3].toFloat().coerceAtLeast(1f)
        val endCx = bounds[0] + endW / 2f
        val endCy = bounds[1] + endH / 2f
        val startCx = screenW / 2f
        val startCy = screenH / 2f

        val tX = endCx - startCx + prefs.closeTranslateX
        val tY = endCy - startCy
        val sX = (endW / screenW).coerceAtLeast(0.08f)
        val sY = (endH / screenH).coerceAtLeast(0.08f)

        val targetView = lastLaunchKey?.let { findIconViewByKey(it) }
        closeTargetView = targetView
        if (targetView != null && appDrawer.isVisible) {
            appDrawer.animate().cancel()
            appDrawer.animate().alpha(0f).setDuration(120).withEndAction {
                appDrawer.isVisible = false
                appDrawer.alpha = 1f
                appDrawer.translationY = 0f
            }.start()
        }
        if (targetView == null) {
            closeTargetView = null
            overlay.animate().cancel()
            overlay.isVisible = true
            overlay.translationX = 0f; overlay.translationY = 0f
            scaleHomeForOpen(false, 420)
            overlay.animate().alpha(0.08f).scaleX(0.28f).scaleY(0.28f)
                .setDuration(420).setInterpolator(DecelerateInterpolator(1.5f))
                .withEndAction {
                    overlay.isVisible = false; overlay.alpha = 1f; overlay.translationY = 0f; overlay.translationX = 0f; overlay.scaleX = 1f; overlay.scaleY = 1f; overlay.clipToOutline = false; overlay.outlineProvider = ViewOutlineProvider.BACKGROUND; overlay.rotationX = 0f; overlay.rotationY = 0f
                }.start()
            return
        }
        targetView?.alpha = 0f

        overlay.animate().cancel()
        overlay.isVisible = true
        if (!wasOpenAnimating) {
            overlay.alpha = 1f
            overlay.scaleX = 1f
            overlay.scaleY = 1f
            overlay.translationX = 0f
            overlay.translationY = 0f
        }
        val app = lastLaunchKey?.let { findApp(it) }
        val closeBg = if (app != null) {
            val icon = app.icon.constantState?.newDrawable()?.mutate() ?: app.icon
            val bg = android.graphics.drawable.GradientDrawable().apply { shape = android.graphics.drawable.GradientDrawable.RECTANGLE; cornerRadius = prefs.windowCornerRadius.coerceIn(0, 60) * resources.displayMetrics.density; setColor(lastLaunchColor) }
            val inset = (resources.displayMetrics.density * 120).toInt()
            android.graphics.drawable.LayerDrawable(arrayOf(bg, icon)).apply { setLayerInset(1, inset, inset, inset, inset); setLayerGravity(1, android.view.Gravity.CENTER) }
        } else roundedBg(lastLaunchColor)
        overlay.background = closeBg
        overlay.clipToOutline = true
        val startRadius = prefs.windowCornerRadius.coerceIn(0, 60) * resources.displayMetrics.density
        overlay.outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) {
                outline.setRoundRect(0, 0, view.width, view.height, startRadius)
            }
        }
        overlay.invalidateOutline()
        val newClose = prefs.newCloseAnim
        if ((prefs.tiltAnimation || newClose) && !prefs.powerSaving) {
            overlay.cameraDistance = resources.displayMetrics.density * 7000f
            overlay.pivotX = screenW / 2f
            overlay.pivotY = screenH / 2f
        } else {
            overlay.pivotX = screenW / 2f; overlay.pivotY = screenH / 2f
        }
        overlay.rotationX = 0f; overlay.rotationY = 0f

        val dur = prefs.closeDuration.toLong().coerceAtLeast(120L)
        scaleHomeForOpen(false, dur)

        val startTX = overlay.translationX
        val startTY = overlay.translationY
        val startSX = overlay.scaleX
        val startSY = overlay.scaleY
        val strength = if (newClose) (if (prefs.closeRandomStrength) (20..100).random() else prefs.closeStrength.coerceIn(0,100)) else 50
        val sFactor = strength / 100f
        val arcH = prefs.closeArcHeight.coerceIn(0f, 900f) * (if (newClose) (0.25f + 1.25f * sFactor) else 1f)
        val tilt = (prefs.tiltAnimation && !prefs.powerSaving) || newClose
        val ti = prefs.tiltIntensity.coerceIn(0, 100) / 50f * (if (newClose) (0.5f + 1.2f * sFactor) else 1f)
        val fromDock = dockApps.any { it?.key == lastLaunchKey }
        val dockTilt = newClose && fromDock
        val dockRotX = if (dockTilt) -(14f + 12f * sFactor) else 0f
        val dockRotY = if (dockTilt) ((startTX - tX) / screenW.coerceAtLeast(1f) * 18f).coerceIn(-10f, 10f) else 0f
        val studioWinPts = if (animWinPoints.isEmpty()) parseAnimPoints(prefs.animStudioWindowPoints) else animWinPoints
        val studioWin = computeAnimTilt(studioWinPts, prefs.animStudioPressure, "window")
        if (prefs.newCloseAnim && studioWinPts.isEmpty() && prefs.animStudioPressure == 0) {
            val iconTop = bounds[1] < screenH * 0.33f
            val iconBottom = bounds[1] > screenH * 0.66f
            studioWin[0] = when { iconTop -> -16f * ti; iconBottom -> 10f * ti; else -> -8f * ti }
            studioWin[1] = ((bounds[0] - screenW/2f) / screenW.coerceAtLeast(1f)) * 10f
        }
        val arriveRotX = (if (dockTilt) dockRotX * 0.55f else if (tilt && newClose) -10f * ti else 0f) + studioWin[0]
        val arriveRotY = (if (dockTilt) dockRotY * 0.55f else if (tilt && newClose) 5f * ti else 0f) + studioWin[1]

        val speedF = (prefs.animStudioSpeed.coerceIn(20,200) / 100f)
        val upDur = ((dur * (if (newClose) (0.30f + 0.18f * sFactor) else 0.38f)) / speedF).toLong().coerceAtLeast(60L)
        val downDur = (((dur - (dur * (if (newClose) (0.30f + 0.18f * sFactor) else 0.38f))) / speedF).toLong()).coerceAtLeast(60L)
        val studioOx = studioWin[4] * screenW * 0.15f
        val studioOy = studioWin[5] * screenH * 0.15f
        val midTX = startTX + (tX - startTX) * 0.45f + studioOx
        val midTY = startTY + (tY - startTY) * (if (newClose) (0.25f - 0.10f * sFactor) else 0.35f) - arcH + studioOy
        val midSX = (startSX + (sX - startSX) * 0.45f) * studioWin[3].coerceIn(0.5f,1.5f)
        val midSY = (startSY + (sY - startSY) * 0.45f) * studioWin[3].coerceIn(0.5f,1.5f)

        fun finishClose(arriveRotX: Float = 0f, arriveRotY: Float = 0f, arriveRz: Float = 0f) {
            overlay.isVisible = false
            overlay.alpha = 1f
            overlay.scaleX = 1f
            overlay.scaleY = 1f
            overlay.translationX = 0f
            overlay.translationY = 0f
            overlay.rotationX = 0f
            overlay.rotationY = 0f
            overlay.rotation = 0f
            overlay.pivotX = screenW / 2f
            overlay.pivotY = screenH / 2f
            overlay.clipToOutline = false
            overlay.outlineProvider = ViewOutlineProvider.BACKGROUND
            clearBlur()
            val target = closeTargetView
            closeTargetView = null
            bounceTargetIcon(lastLaunchKey, arriveRotX, arriveRotY, arriveRz)
            if (target != null && target.alpha == 0f) target.alpha = 1f
            val reopenFolder = lastLaunchKey?.takeIf { isFolderKey(it) }?.let { folderIdFromKey(it) }
            if (reopenFolder != null) root.postDelayed({ try { if (!settingsOverlay.isVisible && !appDrawer.isVisible) openFolder(reopenFolder) } catch (_: Exception) {} }, 450)
        }

        fun morphToIcon() {
            try {
                if (app != null) {
                    overlay.background = app.icon.constantState?.newDrawable()?.mutate() ?: app.icon
                    val shape = prefs.iconShape
                    val radii = currentCornerRadii()
                    overlay.clipToOutline = true
                    overlay.outlineProvider = object : ViewOutlineProvider() {
                        override fun getOutline(view: View, outline: Outline) {
                            val w = view.width; val h = view.height
                            if (w <= 0 || h <= 0) return
                            if (radii != null && radii.size == 8) {
                                val path = android.graphics.Path()
                                path.addRoundRect(android.graphics.RectF(0f,0f,w.toFloat(),h.toFloat()), radii, android.graphics.Path.Direction.CW)
                                if (Build.VERSION.SDK_INT >= 30) outline.setPath(path) else @Suppress("DEPRECATION") outline.setConvexPath(path)
                                return
                            }
                            val r = when (shape) {
                                "circle" -> minOf(w,h)/2f
                                "rounded" -> minOf(w,h)*0.22f
                                "squircle" -> minOf(w,h)*0.32f
                                else -> 0f
                            }
                            if (r <= 0f) outline.setRect(0,0,w,h) else outline.setRoundRect(0,0,w,h,r)
                        }
                    }
                    overlay.invalidateOutline()
                }
            } catch (_: Exception) {}
        }

        overlay.animate().cancel()
        if (prefs.animPreset == "genie") {
            overlay.pivotX = overlay.width / 2f
            overlay.pivotY = overlay.height / 2f
            val gDur = dur.coerceAtLeast(260L)
            overlay.animate()
                .translationX(tX).translationY(tY * 0.55f)
                .scaleX(((1f + sX) / 2f)).scaleY(((1f + sY) / 2f))
                .setDuration((gDur * 0.45f).toLong())
                .setInterpolator(DecelerateInterpolator(1.6f))
                .withEndAction {
                    morphToIcon()
                    overlay.animate().cancel()
                    overlay.animate()
                        .translationX(tX).translationY(tY)
                        .scaleX(sX).scaleY(sY)
                        .rotationX(0f).rotationY(0f)
                        .setDuration((gDur * 0.55f).toLong())
                        .setInterpolator(DecelerateInterpolator(2.2f))
                        .withEndAction { finishClose() }
                        .start()
                }.start()
        } else {
            overlay.animate()
                .translationX(midTX).translationY(midTY)
                .scaleX(midSX).scaleY(midSY)
                .rotationX((if (dockTilt) dockRotX else if (tilt) -20f * ti else 0f) + studioWin[0])
                .rotationY((if (dockTilt) dockRotY else if (tilt) 10f * ti else 0f) + studioWin[1])
                .rotation((if (tilt && !dockTilt) 3f * ti else 0f) + studioWin[2])
                .setDuration(upDur)
                .setInterpolator(DecelerateInterpolator(1.2f))
                .withEndAction {
                    morphToIcon()
                    overlay.animate().cancel()
                    overlay.animate()
                        .translationX(tX).translationY(tY)
                        .scaleX(sX).scaleY(sY)
                        .rotationX(arriveRotX).rotationY(arriveRotY)
                        .rotation(studioWin[2] * 0.55f)
                        .setDuration(downDur)
                        .setInterpolator(DecelerateInterpolator(1.8f))
                        .withEndAction { finishClose(arriveRotX, arriveRotY, studioWin[2] * 0.55f) }
                        .start()
                }
                .start()
        }
    }

    private fun bounceTargetIcon(key: String?, arriveRotX: Float = 0f, arriveRotY: Float = 0f, arriveRz: Float = 0f) {
        if (key == null) return
        val view = findIconViewByKey(key) ?: return
        val overshoot = prefs.closeOvershoot.coerceIn(0f, 1.5f)
        val arcOn = prefs.closeArcHeight > 10f
        val newClose = prefs.newCloseAnim
        val iconScale = if (newClose) prefs.closeIconScale.coerceIn(50, 100) / 100f else 1f
        val iconPts = if (animIconPoints.isEmpty()) parseAnimPoints(prefs.animStudioIconPoints) else animIconPoints
        val studioIcon = computeAnimTilt(iconPts, prefs.animStudioPressure, "icon")
        val finalRotX = arriveRotX + studioIcon[0]
        val finalRotY = arriveRotY + studioIcon[1]
        val finalRotZ = arriveRz + studioIcon[2]
        val tiltBounce = newClose && (prefs.tiltAnimation || finalRotX != 0f || finalRotY != 0f || finalRotZ != 0f) && !prefs.powerSaving
        view.animate().cancel()
        view.translationY = 0f
        view.rotationX = finalRotX; view.rotationY = finalRotY; view.rotation = finalRotZ
        if (tiltBounce) {
            view.cameraDistance = resources.displayMetrics.density * 7000f
            view.pivotX = view.width / 2f; view.pivotY = view.height / 2f
        }
        fun settleTilt() {
            if (!tiltBounce) return
            val rx = android.animation.ObjectAnimator.ofFloat(view, View.ROTATION_X, view.rotationX, 0f)
            val ry = android.animation.ObjectAnimator.ofFloat(view, View.ROTATION_Y, view.rotationY, 0f)
            val rz = android.animation.ObjectAnimator.ofFloat(view, View.ROTATION, view.rotation, 0f)
            android.animation.AnimatorSet().apply { playTogether(rx, ry, rz); duration = 340; interpolator = DecelerateInterpolator(2.4f); start() }
        }
        fun jellyWobble() {
            if (!prefs.liquidWobble || prefs.powerSaving) { settleTilt(); return }
            val i = (prefs.liquidWobbleIntensity.coerceIn(0,100) / 100f)
            val amp = 0.10f * (0.4f + 0.6f * i)
            view.scaleX = 1f + amp; view.scaleY = 1f - amp
            view.animate().scaleX(1f - amp*0.7f).scaleY(1f + amp*0.7f).setDuration(110)
                .withEndAction {
                    view.animate().scaleX(1f + amp*0.35f).scaleY(1f - amp*0.35f).setDuration(90)
                        .withEndAction { view.animate().scaleX(1f).scaleY(1f).setDuration(80).withEndAction { settleTilt() }.start() }
                        .start()
                }.start()
        }
        if (arcOn && overshoot > 0.01f) {
            val drop = (4f + overshoot * 10f) * resources.displayMetrics.density
            val startS = (0.92f * iconScale).coerceAtLeast(0.5f)
            view.scaleX = startS; view.scaleY = startS
            view.translationY = drop
            view.alpha = 1f
            view.animate().scaleX(1f).scaleY(1f).translationY(0f)
                .setDuration(280)
                .setInterpolator(OvershootInterpolator(1.0f + overshoot * 0.8f))
                .withEndAction {
                    if (tiltBounce) { view.rotationX = finalRotX * -0.35f; view.rotationY = finalRotY * -0.35f; view.rotation = finalRotZ * -0.35f; settleTilt() }
                    jellyWobble()
                }.start()
        } else {
            val startS = (0.94f * iconScale).coerceAtLeast(0.5f)
            view.scaleX = startS; view.scaleY = startS
            view.alpha = 1f
            view.animate().scaleX(1f).scaleY(1f).setDuration(180)
                .setInterpolator(DecelerateInterpolator(1.5f))
                .withEndAction { if (tiltBounce) settleTilt(); jellyWobble() }.start()
        }
    }

    private fun findIconViewByKey(key: String): View? {
        for (i in 0 until gridRecycler.childCount) {
            val child = gridRecycler.getChildAt(i) ?: continue
            val pos = gridRecycler.getChildAdapterPosition(child)
            if (pos >= 0 && gridAdapter.getSlot(pos)?.key == key) return child.findViewById(R.id.appIcon) ?: child
        }
        for (i in 0 until dockBar.childCount) {
            val child = dockBar.getChildAt(i) ?: continue
            val label = child.findViewById<TextView>(R.id.appLabel)?.text?.toString()
            val app = dockApps.getOrNull(i)
            if (app?.key == key) return child.findViewById(R.id.appIcon) ?: child
        }
        return null
    }

    private fun expandOverlayToFullscreen() {
        val lp = (animationOverlay.layoutParams as? FrameLayout.LayoutParams) ?: return
        val l = -root.paddingLeft; val t = -root.paddingTop; val r = -root.paddingRight; val b = -root.paddingBottom
        if (lp.leftMargin != l || lp.topMargin != t || lp.rightMargin != r || lp.bottomMargin != b) {
            lp.setMargins(l, t, r, b)
            animationOverlay.layoutParams = lp
        }
    }

    private fun applyBlur(enable: Boolean) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (enable && prefs.blurEnabled && !prefs.powerSaving) {
                val r = prefs.blurRadius.coerceIn(1f, 60f)
                val eff = RenderEffect.createBlurEffect(r, r, Shader.TileMode.CLAMP)
                homeContent.setRenderEffect(eff)
                wallpaperBlurView?.setRenderEffect(eff)
            } else {
                homeContent.setRenderEffect(null)
                wallpaperBlurView?.setRenderEffect(null)
            }
        }
    }

    private fun clearBlur() = applyBlur(false)

    private fun playDockBounce() {
        val d = resources.displayMetrics.density
        val baseS = prefs.dockScale.coerceIn(70,140) / 100f
        val dur = prefs.dockBounceMs.coerceIn(0, 2000)
        if (dur <= 0) { dockContainer.scaleX = baseS; dockContainer.scaleY = baseS; return }
        dockContainer.animate().cancel()
        dockContainer.scaleX = baseS; dockContainer.scaleY = baseS
        val amp = 0.08f
        val t1 = (dur * 0.28f).toLong().coerceAtLeast(40)
        val t2 = (dur * 0.24f).toLong().coerceAtLeast(40)
        val t3 = (dur * 0.24f).toLong().coerceAtLeast(40)
        val t4 = (dur - t1 - t2 - t3).coerceAtLeast(40)
        dockContainer.animate().scaleX(baseS * (1f + amp)).scaleY(baseS * (1f - amp * 0.6f)).setDuration(t1).setInterpolator(DecelerateInterpolator()).withEndAction {
            dockContainer.animate().scaleX(baseS * (1f - amp * 0.55f)).scaleY(baseS * (1f + amp * 0.45f)).setDuration(t2).setInterpolator(DecelerateInterpolator()).withEndAction {
                dockContainer.animate().scaleX(baseS * (1f + amp * 0.25f)).scaleY(baseS * (1f - amp * 0.2f)).setDuration(t3).setInterpolator(DecelerateInterpolator()).withEndAction {
                    dockContainer.animate().scaleX(baseS).scaleY(baseS).setDuration(t4).setInterpolator(DecelerateInterpolator()).start()
                }.start()
            }.start()
        }.start()
    }

    private fun animateDock(show: Boolean, duration: Long = 180L) {
        val d = resources.displayMetrics.density
        val baseX = prefs.dockXOffset * d
        val baseY = prefs.dockYOffset * d
        val baseS = prefs.dockScale.coerceIn(70,140) / 100f
        if (!prefs.dockHideOnOpen || prefs.powerSaving) {
            dockContainer.animate().cancel()
            dockContainer.translationX = baseX; dockContainer.translationY = baseY
            dockContainer.alpha = 1f; dockContainer.scaleX = baseS; dockContainer.scaleY = baseS
            return
        }
        val landscape = resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE
        val dockPos = currentDockPosition(landscape)
        val side = dockPos in listOf("left","right")
        val genie = prefs.dockHideStyle == "genie"
        val hideTx = baseX + when {
            genie && side && dockPos == "right" -> 140f * d
            genie && side && dockPos == "left" -> -140f * d
            genie -> 0f
            else -> 0f
        }
        val hideTy = baseY + when {
            genie && side -> 0f
            else -> 140f * d
        }
        val hideS = if (genie) baseS * 0.35f else baseS
        dockContainer.animate().cancel()
        if (show) {
            dockContainer.translationX = hideTx; dockContainer.translationY = hideTy
            dockContainer.alpha = 0f; dockContainer.scaleX = hideS; dockContainer.scaleY = hideS
            val anim = dockContainer.animate().translationX(baseX).translationY(baseY).alpha(1f).scaleX(baseS).scaleY(baseS)
                .setDuration(if (genie) 320 else duration).setInterpolator(DecelerateInterpolator(1.8f))
            if (genie) {
                anim.withEndAction { playDockBounce() }
            }
            anim.start()
        } else {
            dockContainer.animate().translationX(hideTx).translationY(hideTy).alpha(0f).scaleX(hideS).scaleY(hideS)
                .setDuration(if (genie) 280 else duration).setInterpolator(DecelerateInterpolator(1.6f)).start()
        }
    }

    private fun scaleHomeForOpen(opening: Boolean, duration: Long = 260L) {
        val bs = (prefs.screenScale.coerceIn(50,200) / 100f)
        val zoomMode = if (opening) prefs.openZoomMode else prefs.closeZoomMode
        val zoomAmt = if (opening) prefs.openZoomAmount else prefs.closeZoomAmount
        val zoomF = 1f + (zoomAmt.coerceIn(0,100) / 100f) * 0.18f
        val target = when (zoomMode) {
            "in" -> bs * zoomF
            "out" -> bs / zoomF
            else -> if (opening) 0.88f * bs else bs
        }
        val alpha = if (opening) 0.72f else 1f
        val speed = (if (opening) prefs.openZoomSpeed else 100).coerceIn(20,200) / 100f
        val dur = (duration / speed).toLong().coerceAtLeast(80L)
        homeContent.animate().cancel()
        homeContent.animate().scaleX(target).scaleY(target).alpha(alpha).setDuration(dur).start()
    }

    private fun glassBg(radiusDp: Float = 20f, alpha: Int = 0x55, light: Boolean = prefs.lightMode): Drawable {
        val d = android.graphics.drawable.GradientDrawable()
        d.shape = android.graphics.drawable.GradientDrawable.RECTANGLE
        d.cornerRadius = radiusDp * resources.displayMetrics.density
        val base = if (light) 0xFFFFFF else 0x000000
        d.setColor((alpha shl 24) or base)
        d.setStroke((1 * resources.displayMetrics.density).toInt(), if (light) 0x33000000 else 0x55FFFFFF)
        return d
    }

    private fun applyDockStyle() {
        try {
            val r = prefs.dockRadius.coerceIn(0, 50) * resources.displayMetrics.density
            val d = android.graphics.drawable.GradientDrawable()
            d.cornerRadius = r
            val bgStyle = prefs.dockBg
            val trans = prefs.dockTransparency.coerceIn(0,100)
            fun withTrans(baseAlpha: Int): Int { val a = (baseAlpha * (100 - trans) / 100f).toInt().coerceIn(0,255); return (a shl 24) or 0xFFFFFF }
            fun withTransDark(baseAlpha: Int): Int { val a = (baseAlpha * (100 - trans) / 100f).toInt().coerceIn(0,255); return (a shl 24) or 0x000000 }
            when {
                bgStyle == "transparent" -> { d.setColor(0x00000000); d.setStroke(0, 0) }
                bgStyle == "solid" -> { d.setColor(if (prefs.lightMode) withTrans(0xCC) else withTransDark(0xCC)); d.setStroke(if (trans > 80) 0 else (1 * resources.displayMetrics.density).toInt(), 0x33FFFFFF) }
                bgStyle == "glass" || prefs.liquidGlass -> {
                    d.setColor(if (prefs.lightMode) withTrans(0x55) else withTrans(0x44))
                    d.setStroke(if (trans > 90) 0 else (1 * resources.displayMetrics.density).toInt(), 0x66FFFFFF)
                }
                else -> { d.setColor(if (prefs.lightMode) withTrans(0x66) else withTransDark(0x66)); d.setStroke(if (trans > 90) 0 else (1 * resources.displayMetrics.density).toInt(), 0x33FFFFFF) }
            }
            dockBar.background = d
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) dockBar.setRenderEffect(null)
        } catch (_: Exception) {}
    }

    private fun updateDockPreview() {
        try {
            val preview = findViewById<FrameLayout?>(R.id.dockPreview) ?: return
            val bar = findViewById<LinearLayout?>(R.id.dockPreviewBar) ?: return
            bar.removeAllViews()
            val d = resources.displayMetrics.density
            val r = prefs.dockRadius.coerceIn(0, 50) * d
            val bg = android.graphics.drawable.GradientDrawable().apply { cornerRadius = r; setColor(if (prefs.lightMode) 0x66FFFFFF else 0x66333333); setStroke((1*d).toInt(), 0x55FFFFFF) }
            bar.background = bg
            val lp = bar.layoutParams as FrameLayout.LayoutParams
            when (prefs.dockLayout) {
                "orbit" -> { lp.width = (64*d).toInt(); lp.height = (100*d).toInt(); lp.gravity = android.view.Gravity.END or android.view.Gravity.CENTER_VERTICAL; lp.marginEnd = (8*d).toInt(); lp.bottomMargin = 0; bar.orientation = LinearLayout.VERTICAL }
                "harbor" -> { lp.width = (150*d).toInt(); lp.height = (44*d).toInt(); lp.gravity = (if (prefs.dockCornerMode == "bottom-right") android.view.Gravity.END else android.view.Gravity.START) or android.view.Gravity.BOTTOM; lp.marginStart = (8*d).toInt(); lp.marginEnd = (8*d).toInt(); lp.bottomMargin = (8*d).toInt(); bar.orientation = LinearLayout.HORIZONTAL }
                else -> { lp.width = (220*d).toInt(); lp.height = (52*d).toInt(); lp.gravity = android.view.Gravity.BOTTOM or android.view.Gravity.CENTER_HORIZONTAL; lp.bottomMargin = (8*d).toInt(); lp.marginStart = 0; lp.marginEnd = 0; bar.orientation = LinearLayout.HORIZONTAL }
            }
            bar.layoutParams = lp
            bar.gravity = android.view.Gravity.CENTER
            val count = 4
            for (i in 0 until count) {
                val v = View(this)
                val s = (24*d).toInt()
                val mlp = LinearLayout.LayoutParams(s, s)
                mlp.setMargins((4*d).toInt(), (4*d).toInt(), (4*d).toInt(), (4*d).toInt())
                v.layoutParams = mlp
                val iconBg = android.graphics.drawable.GradientDrawable(); iconBg.cornerRadius = 8*d; iconBg.setColor(0x88FFFFFF.toInt())
                v.background = iconBg
                bar.addView(v)
            }
        } catch (_: Exception) {}
    }

    private fun themePresetLabel(p: String) = when(p) { "iphone8" -> "iPhone 8 style"; "ios26" -> "iOS 26 style"; "ios18" -> "iOS 18 style"; else -> "Android style" }

    private fun applyThemePreset(p: String) {
        prefs.themePreset = p
        when (p) {
            "iphone8" -> {
                prefs.dockRadius = 0; prefs.dockWidth = 100; prefs.dockHeight = 84; prefs.dockBg = "glass"; prefs.dockTransparency = 25
                prefs.iconShape = "rounded"; prefs.drawerFolderRadius = 10
            }
            "ios26" -> {
                prefs.dockRadius = 32; prefs.dockWidth = 92; prefs.dockHeight = 88; prefs.dockBg = "glass"; prefs.dockTransparency = 35
                prefs.iconShape = "squircle"; prefs.drawerFolderRadius = 28; prefs.liquidGlass = true
            }
            "ios18" -> {
                prefs.dockRadius = 28; prefs.dockWidth = 94; prefs.dockHeight = 84; prefs.dockBg = "blur"; prefs.dockTransparency = 30
                prefs.iconShape = "squircle"; prefs.drawerFolderRadius = 24
            }
            else -> {
                prefs.dockRadius = 28; prefs.dockWidth = 100; prefs.dockHeight = 92; prefs.dockBg = "blur"; prefs.dockTransparency = 40
                prefs.iconShape = "circle"; prefs.drawerFolderRadius = 24; prefs.liquidGlass = false
            }
        }
        findViewById<TextView?>(R.id.themePresetLabel)?.text = "Текущий: ${themePresetLabel(p)}"
        applyDockStyle(); applyOrientationLayout(); renderDock(); renderCurrentPage(); updateDockPreview(); applyVisualStyle()
    }

    private fun showThemePresetPicker() {
        val opts = arrayOf("iPhone 8 style", "iOS 26 style", "iOS 18 style", "Android style")
        val vals = arrayOf("iphone8", "ios26", "ios18", "android")
        val checked = vals.indexOf(prefs.themePreset).coerceAtLeast(0)
        AlertDialog.Builder(this).setTitle("Выберите пресет").setSingleChoiceItems(opts, checked) { d, w ->
            applyThemePreset(vals[w]); d.dismiss()
        }.setNegativeButton("Отмена", null).show()
    }

    private fun applyVisualStyle() {
        applyDockStyle()
        val glass = prefs.liquidGlass || prefs.blurEnabled
        val cardBg = if (glass) glassBg(24f, if (prefs.lightMode) 0x66 else 0x44, prefs.lightMode) else getDrawable(R.drawable.bg_settings_panel)
        try { findViewById<View>(R.id.iconMenuCard).background = cardBg } catch (_: Exception) {}
        try { findViewById<View>(R.id.folderCard).background = cardBg } catch (_: Exception) {}
        try { findViewById<View>(R.id.quickPanel).background = if (glass) glassBg(28f, if (prefs.lightMode) 0x55 else 0x33, prefs.lightMode) else getDrawable(R.drawable.bg_settings_panel) } catch (_: Exception) {}
        val textColor = if (prefs.lightMode) 0xFF111111.toInt() else 0xFFFFFFFF.toInt()
        try { findViewById<TextView>(R.id.folderTitle).setTextColor(textColor) } catch (_: Exception) {}
    }

    private fun roundedBg(color: Int): Drawable {
        val d = android.graphics.drawable.GradientDrawable()
        d.shape = android.graphics.drawable.GradientDrawable.RECTANGLE
        d.cornerRadius = prefs.windowCornerRadius.coerceIn(0, 60) * resources.displayMetrics.density
        d.setColor(color)
        return d
    }

    private fun extractColor(icon: Drawable): Int {
        return try {
            val bmp = icon.toBitmap(64, 64)
            Palette.from(bmp).generate().getDominantColor(Color.DKGRAY)
        } catch (_: Exception) {
            Color.DKGRAY
        }
    }
}
