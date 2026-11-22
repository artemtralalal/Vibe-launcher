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
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import android.view.ViewOutlineProvider
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
    private lateinit var iconPackManager: IconPackManager

    private val allApps = mutableListOf<AppEntry>()
    private val dockApps = arrayListOf<AppEntry?>(null, null, null, null)
    private val pages = mutableListOf<MutableList<AppEntry?>>()
    private var currentPageIndex = 0
    private var pageSize = 24

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

    private val unlockReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == Intent.ACTION_USER_PRESENT) playUnlockAnimation()
        }
    }

    private val wallpaperPickLauncher = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) showWallpaperTargetDialog(uri)
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

    private lateinit var volumePanel: View
    private lateinit var audioManager: android.media.AudioManager
    private var volumeHideRunnable: Runnable? = null
    private val volumeReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == "android.media.VOLUME_CHANGED_ACTION") showVolumePanel()
        }
    }

    private var lastLaunchKey: String? = null
    private var lastLaunchBounds: IntArray? = null
    private var lastLaunchColor: Int = Color.DKGRAY
    private var pendingLaunch = false
    private var openAnimating = false
    private var cameraGeniePending = false
    private var closeAnimator: android.animation.ValueAnimator? = null
    private var closeTargetView: View? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        prefs = LauncherPrefs(this)
        iconPackManager = IconPackManager(this)
        root = findViewById(R.id.root)
        homeContent = findViewById(R.id.homeContent)
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
        setupBack()
        setupPageEdges()
        setupVolumePanel()
        setupAbout()
        applyOrientationLayout()
        loadFoldersFromPrefs()
        loadApps()
        setupWelcome()
        applyPowerSavingMode()
        applyVisualStyle()
        try { registerReceiver(unlockReceiver, IntentFilter(Intent.ACTION_USER_PRESENT)) } catch (_: Exception) {}
        try { registerReceiver(volumeReceiver, IntentFilter("android.media.VOLUME_CHANGED_ACTION")) } catch (_: Exception) {}
    }

    override fun onDestroy() {
        try { unregisterReceiver(unlockReceiver) } catch (_: Exception) {}
        try { unregisterReceiver(volumeReceiver) } catch (_: Exception) {}
        try { unlockPlayer?.release() } catch (_: Exception) {}
        super.onDestroy()
    }

    override fun onResume() {
        super.onResume()
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

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        try {
            applyOrientationLayout()
            renderDock()
            renderCurrentPage()
        } catch (_: Exception) { }
    }

    private fun applyOrientationLayout() {
        val landscape = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        val homeContent = findViewById<LinearLayout>(R.id.homeContent)
        val workspace = findViewById<FrameLayout>(R.id.workspaceContainer)
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

        homeContent.setPadding(sideMargin, (24 * d).toInt(), sideMargin, (20 * d).toInt())
        gridRecycler.setPadding((gridPad * d).toInt(), 0, (gridPad * d).toInt(), (8 * d).toInt())
        val lm = gridRecycler.layoutManager
        if (lm is GridLayoutManager) {
            if (lm.spanCount != cols) gridRecycler.layoutManager = GridLayoutManager(this, cols)
        } else {
            gridRecycler.layoutManager = GridLayoutManager(this, cols)
        }
        gridRecycler.scaleX = iconScale
        gridRecycler.scaleY = iconScale
        gridRecycler.pivotX = gridRecycler.width / 2f
        gridRecycler.pivotY = 0f

        val indicator = findViewById<LinearLayout>(R.id.pageIndicator)
        if (landscape && prefs.landscapeDockSide == "right") {
            homeContent.orientation = LinearLayout.HORIZONTAL
            (workspace.layoutParams as LinearLayout.LayoutParams).apply {
                width = 0; height = LinearLayout.LayoutParams.MATCH_PARENT; weight = 1f; bottomMargin = 0; marginEnd = (8*d).toInt()
            }.also { workspace.layoutParams = it }
            (dockContainer.layoutParams as LinearLayout.LayoutParams).apply {
                width = LinearLayout.LayoutParams.WRAP_CONTENT; height = LinearLayout.LayoutParams.MATCH_PARENT; weight = 0f; topMargin = 0
            }.also { dockContainer.layoutParams = it }
            dockContainer.orientation = LinearLayout.VERTICAL
            dockContainer.gravity = android.view.Gravity.CENTER
            (dockBar.layoutParams as LinearLayout.LayoutParams).apply {
                width = (88 * d).toInt(); height = 0; weight = 1f
            }.also { dockBar.layoutParams = it }
            dockBar.orientation = LinearLayout.VERTICAL
            val pad = (prefs.landscapeDockPadding * d).toInt()
            dockBar.setPadding(pad, pad, pad, pad)
            (drawerBtn.layoutParams as LinearLayout.LayoutParams).apply {
                marginStart = 0; topMargin = (8*d).toInt()
            }.also { drawerBtn.layoutParams = it }
            indicator.isVisible = false
        } else {
            homeContent.orientation = LinearLayout.VERTICAL
            (workspace.layoutParams as LinearLayout.LayoutParams).apply {
                width = LinearLayout.LayoutParams.MATCH_PARENT; height = 0; weight = 1f; marginEnd = 0; bottomMargin = 0
            }.also { workspace.layoutParams = it }
            (dockContainer.layoutParams as LinearLayout.LayoutParams).apply {
                width = LinearLayout.LayoutParams.MATCH_PARENT; height = LinearLayout.LayoutParams.WRAP_CONTENT; weight = 0f; topMargin = (4*d).toInt()
            }.also { dockContainer.layoutParams = it }
            dockContainer.orientation = LinearLayout.HORIZONTAL
            dockContainer.gravity = android.view.Gravity.CENTER_VERTICAL
            (dockBar.layoutParams as LinearLayout.LayoutParams).apply {
                width = 0; height = (92 * d).toInt(); weight = 1f
            }.also { dockBar.layoutParams = it }
            dockBar.orientation = LinearLayout.HORIZONTAL
            dockBar.setPadding((12*d).toInt(), 0, (12*d).toInt(), 0)
            (drawerBtn.layoutParams as LinearLayout.LayoutParams).apply {
                marginStart = (8*d).toInt(); topMargin = 0
            }.also { drawerBtn.layoutParams = it }
            indicator.isVisible = true
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
            iconScale = { prefs.iconScale }
        )
        gridRecycler.layoutManager = GridLayoutManager(this, prefs.portraitGridCols.coerceIn(3, 7))
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
            cornerRadii = { currentCornerRadii() }
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
            }
            override fun afterTextChanged(s: android.text.Editable?) {}
        })
    }

    private fun setupDock() {
        dockBar.setOnDragListener { _, event -> handleDockDrag(event) }
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
        gridAdapter.submitApps(pages[currentPageIndex].toList())
        updatePageIndicator()
        prefs.currentPage = currentPageIndex
    }

    private fun updatePageIndicator() {
        pageIndicator.removeAllViews()
        val count = pages.size.coerceAtLeast(1)
        val d = resources.displayMetrics.density
        for (i in 0 until count) {
            val dot = View(this)
            val size = (if (i == currentPageIndex) 8 else 6) * d
            val lp = LinearLayout.LayoutParams(size.toInt(), size.toInt())
            lp.marginStart = (3 * d).toInt(); lp.marginEnd = (3 * d).toInt()
            dot.layoutParams = lp
            val bg = android.graphics.drawable.GradientDrawable()
            bg.shape = android.graphics.drawable.GradientDrawable.OVAL
            bg.setColor(if (i == currentPageIndex) prefs.accentColor else 0x66FFFFFF)
            dot.background = bg
            val pageIndex = i
            dot.setOnClickListener { showPageManager() }
            dot.setOnLongClickListener { showPageManager(); true }
            pageIndicator.addView(dot)
        }
        pageIndicator.isVisible = true
        pageIndicator.setOnClickListener { showPageManager() }
        pageIndicator.setOnLongClickListener { showPageManager(); true }
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

    private fun switchPageAnimated(delta: Int) {
        ensurePages()
        val next = currentPageIndex + delta
        if (next < 0 || next > pages.lastIndex) return
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

    private fun setupVolumePanel() {
        volumePanel = findViewById(R.id.volumePanel)
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
            volumePanel.alpha = 0f
            volumePanel.isVisible = true
            volumePanel.animate().cancel()
            volumePanel.animate().alpha(1f).setDuration(150).start()
            scheduleVolumeHide()
        } catch (_: Exception) { }
    }

    private fun scheduleVolumeHide() {
        volumeHideRunnable?.let { root.removeCallbacks(it) }
        volumeHideRunnable = Runnable {
            if (::volumePanel.isInitialized) volumePanel.animate().alpha(0f).setDuration(200).withEndAction { volumePanel.isVisible = false }.start()
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
        val dockStyleBtn = findViewById<Button>(R.id.dockStyleBtn)
        fun updateDockStyleLabel() { dockStyleBtn.text = "Стиль дока: ${when(prefs.dockStyle){ "glass"->"Стекло"; "solid"->"Сплошной"; "transparent"->"Прозрачный"; else->"Размытие" }}" }
        updateDockStyleLabel()
        dockStyleBtn.setOnClickListener {
            val opts = listOf("blur" to "Размытие", "glass" to "Стекло", "solid" to "Сплошной", "transparent" to "Прозрачный")
            AlertDialog.Builder(this).setTitle("Стиль дока").setItems(opts.map{it.second}.toTypedArray()) { _, w ->
                prefs.dockStyle = opts[w].first; updateDockStyleLabel(); applyDockStyle()
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
        updateDrawerStyleLabel()
        findViewById<Button>(R.id.drawerStyleBtn).setOnClickListener { showDrawerStylePicker() }
        updateDrawerLayoutLabel()
        findViewById<Button>(R.id.drawerLayoutBtn).setOnClickListener { showDrawerLayoutPicker() }
        updateLandDockLabel()
        findViewById<Button>(R.id.landDockBtn).setOnClickListener { showLandDockPicker() }
        bindSeek(findViewById(R.id.gridRowsSeek), prefs.homeGridRows - 4, 0, 4) { prefs.homeGridRows = it + 4; applyOrientationLayout(); renderCurrentPage(); persistLayout() }
        bindSeek(findViewById(R.id.portColsSeek), prefs.portraitGridCols - 3, 0, 3) { prefs.portraitGridCols = it + 3; applyOrientationLayout(); renderCurrentPage() }
        bindSeek(findViewById(R.id.portPadSeek), prefs.portraitGridPadding, 0, 40) { prefs.portraitGridPadding = it; applyOrientationLayout() }
        bindSeek(findViewById(R.id.landColsSeek), prefs.landscapeGridCols - 4, 0, 4) { prefs.landscapeGridCols = it + 4; applyOrientationLayout(); renderCurrentPage() }
        bindSeek(findViewById(R.id.landPadSeek), prefs.landscapeGridPadding, 0, 40) { prefs.landscapeGridPadding = it; applyOrientationLayout() }
        bindSeek(findViewById(R.id.landScaleSeek), prefs.landscapeIconScale - 60, 0, 60) { prefs.landscapeIconScale = it + 60; applyOrientationLayout() }

        settingsButton.setOnClickListener {
            showSettingsMain()
            settingsOverlay.isVisible = true
            quickPanel.isVisible = false; settingsButton.isVisible = false
        }
        setupSettingsNavigation()
        findViewById<View>(R.id.quickWallpaper).setOnClickListener { quickPanel.isVisible = false; openWallpaperPicker() }
        findViewById<View>(R.id.quickSettings).setOnClickListener { quickPanel.isVisible = false; showSettingsMain(); settingsOverlay.isVisible = true }
        findViewById<View>(R.id.quickIcons).setOnClickListener { quickPanel.isVisible = false; showIconShapePicker() }
        findViewById<View>(R.id.quickWidgets).setOnClickListener { quickPanel.isVisible = false; android.widget.Toast.makeText(this, "Виджеты скоро будут добавлены", android.widget.Toast.LENGTH_SHORT).show() }
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
            "ripple" -> "Ripple (отпечаток)"; "wave" -> "Волна"; "fire" -> "Огонь"; "ice" -> "Лёд"
            "samsung" -> "Samsung One UI"; "oneplus" -> "OnePlus"; "nothing" -> "Nothing"; "hyperos" -> "HyperOS"
            "miui13" -> "MIUI 13"; "oukitel" -> "Oukitel / Чистый Android"; "none" -> "Нет"; else -> prefs.unlockAnim
        }
        findViewById<TextView>(R.id.unlockAnimLabel).text = "Анимация разблокировки: $label"
    }

    private fun showUnlockAnimPicker() {
        val opts = listOf(
            "pixel" to "Pixel", "poco" to "POCO/Xiaomi", "ios" to "iPhone",
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
        val label = if (prefs.drawerStyle == "button") "Кнопка в доке" else "Свайп вверх"
        findViewById<TextView>(R.id.drawerStyleLabel).text = "Меню приложений: $label"
    }

    private fun showDrawerStylePicker() {
        val opts = listOf("swipe" to "Свайп вверх", "button" to "Кнопка в доке")
        AlertDialog.Builder(this).setTitle("Стиль меню приложений")
            .setItems(opts.map { it.second }.toTypedArray()) { _, which ->
                prefs.drawerStyle = opts[which].first
                updateDrawerStyleLabel()
                renderDock()
            }.show()
    }

    private fun updateDrawerLayoutLabel() {
        val label = when (prefs.drawerLayout) { "list" -> "Список"; else -> "Сетка" }
        findViewById<TextView>(R.id.drawerLayoutLabel).text = "Вид меню: $label"
    }

    private fun showDrawerLayoutPicker() {
        val opts = listOf("grid" to "Сетка", "list" to "Список")
        AlertDialog.Builder(this).setTitle("Вид меню приложений")
            .setItems(opts.map { it.second }.toTypedArray()) { _, which ->
                prefs.drawerLayout = opts[which].first
                updateDrawerLayoutLabel()
                applyDrawerLayout()
            }.show()
    }

    private fun showDrawerSettingsDialog() {
        val opts = arrayOf(
            if (prefs.drawerIpadMode) "Режим iPad: вкл" else "Режим iPad: выкл",
            "Вид меню: ${if (prefs.drawerLayout == "list") "Список" else "Сетка"}",
            "Стиль открытия: ${if (prefs.drawerStyle == "button") "Кнопка" else "Свайп"}"
        )
        AlertDialog.Builder(this).setTitle("Настройки меню приложений")
            .setItems(opts) { _, which ->
                when (which) {
                    0 -> { prefs.drawerIpadMode = !prefs.drawerIpadMode; renderDock() }
                    1 -> showDrawerLayoutPicker()
                    2 -> showDrawerStylePicker()
                }
            }.show()
    }

    private fun applyDrawerLayout() {
        if (!::drawerRecycler.isInitialized) return
        drawerRecycler.layoutManager = if (prefs.drawerLayout == "list") LinearLayoutManager(this) else GridLayoutManager(this, 4)
        drawerRecycler.adapter = drawerAdapter
    }

    private fun updateLandDockLabel() {
        val label = if (prefs.landscapeDockSide == "bottom") "Снизу" else "Справа"
        findViewById<TextView>(R.id.landDockLabel).text = "Док в горизонтали: $label"
    }

    private fun showLandDockPicker() {
        val opts = listOf("right" to "Справа", "bottom" to "Снизу")
        AlertDialog.Builder(this).setTitle("Док в горизонтальном режиме")
            .setItems(opts.map { it.second }.toTypedArray()) { _, which ->
                prefs.landscapeDockSide = opts[which].first
                updateLandDockLabel()
                applyOrientationLayout()
                renderDock()
                renderCurrentPage()
            }.show()
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
        findViewById<View>(R.id.catRowAnimations).setOnClickListener { showSettingsCategory("animations") }
        findViewById<View>(R.id.catRowHome).setOnClickListener { showSettingsCategory("home") }
        findViewById<View>(R.id.catRowDrawer).setOnClickListener { showSettingsCategory("drawer") }
        findViewById<View>(R.id.catRowPower).setOnClickListener { showSettingsCategory("power") }
        findViewById<View>(R.id.catRowAbout).setOnClickListener { showSettingsCategory("about") }
    }

    private fun showSettingsMain() {
        findViewById<View>(R.id.settingsMain).isVisible = true
        findViewById<View>(R.id.settingsDetail).isVisible = false
        listOf(R.id.catAppearance, R.id.catAnimations, R.id.catHome, R.id.catDrawer, R.id.catPower, R.id.catAbout).forEach {
            findViewById<View>(it).isVisible = false
        }
    }

    private fun showSettingsCategory(cat: String) {
        findViewById<View>(R.id.settingsMain).isVisible = false
        findViewById<View>(R.id.settingsDetail).isVisible = true
        val map = mapOf(
            "appearance" to (R.id.catAppearance to "Внешний вид"),
            "animations" to (R.id.catAnimations to "Анимации"),
            "home" to (R.id.catHome to "Рабочий стол и экран"),
            "drawer" to (R.id.catDrawer to "Меню приложений"),
            "power" to (R.id.catPower to "Энергосбережение"),
            "about" to (R.id.catAbout to "О лаунчере")
        )
        map.forEach { (k, v) -> findViewById<View>(v.first).isVisible = k == cat }
        findViewById<TextView>(R.id.settingsDetailTitle).text = map[cat]?.second ?: "Настройки"
    }

    private fun setupGestures() {
        globalDetector = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDoubleTap(e: MotionEvent): Boolean {
                if (settingsOverlay.isVisible || appDrawer.isVisible) return false
                if (isPointOnAppIcon(e.rawX, e.rawY)) return false
                launchCameraWithGenie()
                return true
            }

            override fun onLongPress(e: MotionEvent) {
                if (settingsOverlay.isVisible || appDrawer.isVisible) return
                if (!isPointOnAppIcon(e.rawX, e.rawY)) showSettingsButton()
            }

            override fun onFling(e1: MotionEvent?, e2: MotionEvent, velocityX: Float, velocityY: Float): Boolean {
                if (e1 == null) return false
                val dy = e2.y - e1.y
                val dx = e2.x - e1.x
                if (abs(dy) > abs(dx) && dy < -120 && abs(velocityY) > 200) {
                    if (!appDrawer.isVisible && !settingsOverlay.isVisible) { openDrawer(); return true }
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
        handlePageDrag(ev)
        if (::globalDetector.isInitialized) globalDetector.onTouchEvent(ev)
        return super.dispatchTouchEvent(ev)
    }

    private fun handlePageDrag(ev: MotionEvent) {
        if (appDrawer.isVisible || settingsOverlay.isVisible || draggedKey != null) {
            if (pageDragActive) snapPageDrag(0f)
            return
        }
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
                        // don't start page drag if touch started on icon
                        if (!isPointOnAppIcon(ev.rawX - dx, ev.rawY - dy)) pageDragActive = true
                    }
                }
                if (pageDragActive) {
                    pageDragDx = dx
                    val canLeft = currentPageIndex > 0
                    val canRight = currentPageIndex < pages.lastIndex
                    val resisted = when {
                        dx > 0 && !canLeft -> dx * 0.2f
                        dx < 0 && !canRight -> dx * 0.2f
                        else -> dx
                    }
                    gridRecycler.translationX = resisted
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
        val threshold = gridRecycler.width * 0.2f
        val goNext = dx < -threshold && currentPageIndex < pages.lastIndex
        val goPrev = dx > threshold && currentPageIndex > 0
        when {
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
            child.getLocationOnScreen(loc)
            if (rawX >= loc[0] && rawX <= loc[0] + child.width && rawY >= loc[1] && rawY <= loc[1] + child.height) return true
        }
        for (i in 0 until dockBar.childCount) {
            val child = dockBar.getChildAt(i) ?: continue
            child.getLocationOnScreen(loc)
            if (rawX >= loc[0] && rawX <= loc[0] + child.width && rawY >= loc[1] && rawY <= loc[1] + child.height) return true
        }
        return false
    }

    private fun setupBack() {
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                when {
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
                    quickPanel.isVisible -> quickPanel.isVisible = false
                    settingsButton.isVisible -> settingsButton.isVisible = false
                    appDrawer.isVisible -> closeDrawer()
                    else -> {}
                }
            }
        })
    }

    private fun openDrawer() {
        quickPanel.isVisible = false; settingsButton.isVisible = false
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
        autoBtn.setOnClickListener { applyAutoLayout(); finishWelcome() }
        emptyBtn.setOnClickListener { applyEmptyLayout(); finishWelcome() }
    }

    private fun applyAutoLayout() {
        try {
            prefs.layoutInitialized = true
            val dockCandidates = listOf(
                listOf("dialer", "phone", "телефон"),
                listOf("contacts", "people", "контакт"),
                listOf("camera", "камера"),
                listOf("browser", "chrome", "yandex", "firefox", "браузер")
            )
            val used = mutableSetOf<String>()
            for (i in 0 until 4) {
                val match = allApps.firstOrNull { app ->
                    app.key !in used && dockCandidates[i].any { c ->
                        app.label.lowercase().contains(c) || app.packageName.lowercase().contains(c)
                    }
                }
                dockApps[i] = match
                if (match != null) used.add(match.key)
            }
            val rest = allApps.filter { it.key !in used }
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
            val loc = IntArray(2)
            target.getLocationOnScreen(loc)
            val rootLoc = IntArray(2)
            root.getLocationOnScreen(rootLoc)
            val left = loc[0] - rootLoc[0]
            val top = loc[1] - rootLoc[1]
            val cols: Int = 3
            val rows: Int = 3
            val pw: Int = bmp.width / cols
            val ph: Int = bmp.height / rows
            val pieces = mutableListOf<View>()
            for (r in 0 until rows) {
                for (c in 0 until cols) {
                    val px: Int = c * pw
                    val py: Int = r * ph
                    val pieceBmp = android.graphics.Bitmap.createBitmap(bmp, px, py, pw, ph)
                    val iv = ImageView(this)
                    iv.setImageBitmap(pieceBmp)
                    iv.x = (left + px).toFloat()
                    iv.y = (top + py).toFloat()
                    iv.layoutParams = FrameLayout.LayoutParams(pw, ph)
                    root.addView(iv)
                    pieces.add(iv)
                }
            }
            target.alpha = 0f
            val rnd = java.util.Random()
            pieces.forEach { p ->
                val dx = (rnd.nextFloat() - 0.5f) * 280f
                val dy = (rnd.nextFloat() - 0.2f) * 320f
                val rot = (rnd.nextFloat() - 0.5f) * 180f
                p.animate().translationXBy(dx).translationYBy(dy).rotationBy(rot)
                    .alpha(0f).scaleX(0.3f).scaleY(0.3f).setDuration(320)
                    .setInterpolator(DecelerateInterpolator(1.5f)).start()
            }
            root.postDelayed({
                pieces.forEach { try { root.removeView(it) } catch (_: Exception) {} }
                onEnd()
            }, 340)
        } catch (_: Exception) { onEnd() }
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

    private fun loadApps() {
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
                            val savedDock = prefs.loadDockOrder().mapNotNull { byKey[it] }.distinctBy { it.key }.take(4)
                            for (i in 0 until 4) dockApps[i] = savedDock.getOrNull(i)
                            val used = dockApps.filterNotNull().map { it.key }.toMutableSet()
                            val savedPages = prefs.loadPages()
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
        val density = resources.displayMetrics.density
        val vertical = dockBar.orientation == LinearLayout.VERTICAL
        val ipad = prefs.drawerIpadMode
        val slots = if (ipad) 5 else 4
        for (i in 0 until slots) {
            val item = layoutInflater.inflate(R.layout.item_app, dockBar, false)
            val icon = item.findViewById<ImageView>(R.id.appIcon)
            val label = item.findViewById<TextView>(R.id.appLabel)
            label.visibility = View.GONE
            val lp = if (vertical) {
                LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f)
            } else {
                val itemWidth = ((resources.displayMetrics.widthPixels - density * 56) / slots).toInt()
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
            dockBar.addView(item)
        }
        val drawerBtn = findViewById<ImageButton>(R.id.drawerBtn)
        drawerBtn.isVisible = prefs.drawerStyle == "button" && !ipad
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
        val overlay = findViewById<FrameLayout>(R.id.folderOverlay)
        val card = findViewById<LinearLayout>(R.id.folderCard)
        val title = findViewById<TextView>(R.id.folderTitle)
        val rv = findViewById<RecyclerView>(R.id.folderRecycler)
        title.text = folderNames[id] ?: "Папка"
        title.setOnClickListener { renameFolderDialog(id) }
        val all = folders[id].orEmpty().mapNotNull { findApp(it) }
        val small = isSmallFolder(id)
        val cols = folderColumns(id).coerceIn(2, 5)
        rv.layoutManager = GridLayoutManager(this, cols)
        val shown = if (small && all.size > 4) all.take(3) else all
        val ad = DrawerAdapter(
            onClick = { app, icon ->
                lastLaunchKey = folderKey(id)
                overlay.isVisible = false
                launchApp(app, icon, fromDrawer = false)
            },
            onLongPress = { app, view -> showIconMenu(app, view, fromDock = null, fromDrawer = true); true },
            shape = { prefs.iconShape },
            cornerRadii = { currentCornerRadii() }
        )
        rv.adapter = ad
        ad.submit(shown)
        // Show "open all" hint for small folders with overflow
        var moreBtn = card.findViewWithTag<TextView>("folderMore")
        if (small && all.size > 4) {
            if (moreBtn == null) {
                moreBtn = TextView(this).apply {
                    tag = "folderMore"
                    setTextColor(0xCCFFFFFF.toInt())
                    textSize = 14f
                    gravity = android.view.Gravity.CENTER
                    setPadding(0, dp(8), 0, 0)
                }
                card.addView(moreBtn)
            }
            moreBtn.text = "Показать все (${all.size})"
            moreBtn.isVisible = true
            moreBtn.setOnClickListener {
                ad.submit(all)
                moreBtn.isVisible = false
            }
        } else {
            moreBtn?.isVisible = false
        }
        overlay.isVisible = true
        overlay.alpha = 0f
        overlay.animate().alpha(1f).setDuration(180).start()
        overlay.setOnClickListener { closeFolderOverlay() }
        card.setOnClickListener { }
    }

    private fun closeFolderOverlay() {
        val overlay = findViewById<FrameLayout>(R.id.folderOverlay)
        overlay.animate().alpha(0f).setDuration(150).withEndAction { overlay.isVisible = false; overlay.alpha = 1f }.start()
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
            if (!fromDrawer) {
                addOption("Убрать с рабочего стола") {
                    animateShatter(view) {
                        if (fromDock != null) dockApps[fromDock] = null else gridAdapter.removeByKey(app.key)
                        renderDock(); persistLayout(); updatePageIndicator()
                    }
                }
            }
            addOption("Удалить с телефона", danger = true) {
                try { startActivity(Intent(Intent.ACTION_DELETE, android.net.Uri.parse("package:${app.packageName}"))) } catch (_: Exception) { }
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
            homeContent.setRenderEffect(RenderEffect.createBlurEffect(30f,30f, Shader.TileMode.CLAMP))
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
            } else if (fromDrawer && !isFolderKey(occupant.key) && !isFolderKey(app.key)) {
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
            val ws = findViewById<android.widget.FrameLayout>(R.id.workspaceContainer)
            if (dragGridOverlay == null) {
                dragGridOverlay = DragGridOverlay(this).apply {
                    layoutParams = android.widget.FrameLayout.LayoutParams(android.widget.FrameLayout.LayoutParams.MATCH_PARENT, android.widget.FrameLayout.LayoutParams.MATCH_PARENT)
                    isClickable = false; isFocusable = false
                }
                ws.addView(dragGridOverlay)
            }
            val cols = (gridRecycler.layoutManager as? GridLayoutManager)?.spanCount ?: 4
            dragGridOverlay?.cols = cols
            dragGridOverlay?.rows = ((pageSize + cols - 1) / cols).coerceAtLeast(1)
            dragGridOverlay?.visibility = View.VISIBLE
            dragGridOverlay?.bringToFront()
            // keep grid above overlay
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

    private fun persistLayout() {
        ensurePages()
        pages[currentPageIndex] = gridAdapter.allSlots().toMutableList()
        while (pages.size > 1 && pages.last().all { it == null }) pages.removeAt(pages.lastIndex)
        val clamped = currentPageIndex.coerceIn(0, pages.lastIndex)
        val changed = clamped != currentPageIndex
        currentPageIndex = clamped
        prefs.savePages(pages.map { p -> p.map { it?.key } })
        prefs.currentPage = currentPageIndex
        prefs.saveDockOrder(dockApps.mapNotNull { it?.key })
        if (changed) renderCurrentPage() else updatePageIndicator()
    }

    private fun launchApp(app: AppEntry, iconView: View, fromDrawer: Boolean) {
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
        val overlay = animationOverlay
        overlay.animate().cancel()
        overlay.background = roundedBg(prefs.accentColor)
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

        val overlay = animationOverlay
        overlay.background = roundedBg(color)
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
        if (prefs.tiltAnimation && !prefs.powerSaving) {
            val ti = prefs.tiltIntensity.coerceIn(0, 100) / 50f
            overlay.cameraDistance = resources.displayMetrics.density * 7000f
            overlay.pivotX = overlay.width / 2f
            overlay.pivotY = overlay.height / 2f
            overlay.rotationX = 10f * ti
            overlay.rotationY = (if (tX < 0) 6f else -6f) * ti
        } else {
            overlay.rotationX = 0f; overlay.rotationY = 0f; overlay.pivotX = overlay.width / 2f; overlay.pivotY = overlay.height / 2f
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
        overlay.background = roundedBg(lastLaunchColor)
        overlay.clipToOutline = true
        val startRadius = prefs.windowCornerRadius.coerceIn(0, 60) * resources.displayMetrics.density
        overlay.outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) {
                outline.setRoundRect(0, 0, view.width, view.height, startRadius)
            }
        }
        if (prefs.tiltAnimation && !prefs.powerSaving) {
            overlay.cameraDistance = resources.displayMetrics.density * 7000f
            overlay.pivotX = overlay.width / 2f
            overlay.pivotY = overlay.height / 2f
        } else {
            overlay.pivotX = overlay.width / 2f; overlay.pivotY = overlay.height / 2f
        }
        overlay.rotationX = 0f; overlay.rotationY = 0f

        val dur = prefs.closeDuration.toLong().coerceAtLeast(120L)
        scaleHomeForOpen(false, dur)
        val app = lastLaunchKey?.let { findApp(it) }

        val startTX = overlay.translationX
        val startTY = overlay.translationY
        val startSX = overlay.scaleX
        val startSY = overlay.scaleY
        val arcH = prefs.closeArcHeight.coerceIn(0f, 900f)
        val tilt = prefs.tiltAnimation && !prefs.powerSaving
        val ti = prefs.tiltIntensity.coerceIn(0, 100) / 50f

        val upDur = (dur * 0.38f).toLong().coerceAtLeast(60L)
        val downDur = (dur - upDur).coerceAtLeast(60L)
        val midTX = startTX + (tX - startTX) * 0.45f
        val midTY = startTY + (tY - startTY) * 0.35f - arcH
        val midSX = startSX + (sX - startSX) * 0.45f
        val midSY = startSY + (sY - startSY) * 0.45f

        fun finishClose() {
            overlay.isVisible = false
            overlay.alpha = 1f
            overlay.scaleX = 1f
            overlay.scaleY = 1f
            overlay.translationX = 0f
            overlay.translationY = 0f
            overlay.rotationX = 0f
            overlay.rotationY = 0f
            overlay.pivotX = overlay.width / 2f
            overlay.pivotY = overlay.height / 2f
            overlay.clipToOutline = false
            overlay.outlineProvider = ViewOutlineProvider.BACKGROUND
            clearBlur()
            closeTargetView?.alpha = 1f
            closeTargetView = null
            bounceTargetIcon(lastLaunchKey)
        }

        fun morphToIcon() {
            try {
                if (app != null) {
                    overlay.background = app.icon.constantState?.newDrawable()?.mutate() ?: app.icon
                    val shape = prefs.iconShape
                    val radii = currentCornerRadii()
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
                .rotationX(if (tilt) -10f * ti else 0f).rotationY(if (tilt) 4f * ti else 0f)
                .setDuration(upDur)
                .setInterpolator(DecelerateInterpolator(1.2f))
                .withEndAction {
                    morphToIcon()
                    overlay.animate().cancel()
                    overlay.animate()
                        .translationX(tX).translationY(tY)
                        .scaleX(sX).scaleY(sY)
                        .rotationX(0f).rotationY(0f)
                        .setDuration(downDur)
                        .setInterpolator(DecelerateInterpolator(1.8f))
                        .withEndAction { finishClose() }
                        .start()
                }
                .start()
        }
    }

    private fun bounceTargetIcon(key: String?) {
        if (key == null) return
        val view = findIconViewByKey(key) ?: return
        val overshoot = prefs.closeOvershoot.coerceIn(0f, 1.5f)
        val arcOn = prefs.closeArcHeight > 10f
        view.animate().cancel()
        view.translationY = 0f
        fun jellyWobble() {
            if (!prefs.liquidWobble || prefs.powerSaving) return
            val i = (prefs.liquidWobbleIntensity.coerceIn(0,100) / 100f)
            val amp = 0.10f * (0.4f + 0.6f * i)
            view.scaleX = 1f + amp; view.scaleY = 1f - amp
            view.animate().scaleX(1f - amp*0.7f).scaleY(1f + amp*0.7f).setDuration(110)
                .withEndAction {
                    view.animate().scaleX(1f + amp*0.35f).scaleY(1f - amp*0.35f).setDuration(90)
                        .withEndAction { view.animate().scaleX(1f).scaleY(1f).setDuration(80).start() }
                        .start()
                }.start()
        }
        if (arcOn && overshoot > 0.01f) {
            val drop = (4f + overshoot * 10f) * resources.displayMetrics.density
            view.scaleX = 0.92f
            view.scaleY = 0.92f
            view.translationY = drop
            view.animate().scaleX(1f).scaleY(1f).translationY(0f).setDuration(280)
                .setInterpolator(OvershootInterpolator(1.0f + overshoot * 0.8f))
                .withEndAction { jellyWobble() }.start()
        } else {
            view.scaleX = 0.94f; view.scaleY = 0.94f
            view.animate().scaleX(1f).scaleY(1f).setDuration(160)
                .setInterpolator(DecelerateInterpolator(1.5f))
                .withEndAction { jellyWobble() }.start()
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

    private fun applyBlur(enable: Boolean) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (enable && prefs.blurEnabled && !prefs.powerSaving) {
                val r = prefs.blurRadius.coerceIn(1f, 60f)
                homeContent.setRenderEffect(RenderEffect.createBlurEffect(r, r, Shader.TileMode.CLAMP))
            } else {
                homeContent.setRenderEffect(null)
            }
        }
    }

    private fun clearBlur() = applyBlur(false)

    private fun animateDock(show: Boolean, duration: Long = 180L) {
        if (!prefs.dockHideOnOpen || prefs.powerSaving) {
            dockContainer.translationY = 0f; dockContainer.alpha = 1f; dockContainer.scaleX = 1f; dockContainer.scaleY = 1f; dockContainer.translationX = 0f; return
        }
        val d = resources.displayMetrics.density
        val hideY = 120f * d
        val hideX = if (prefs.dockHideStyle == "genie") (resources.displayMetrics.widthPixels * 0.35f) else 0f
        dockContainer.animate().cancel()
        if (show) {
            dockContainer.translationY = hideY; dockContainer.translationX = hideX; dockContainer.alpha = 0f
            dockContainer.scaleX = if (prefs.dockHideStyle == "genie") 0.4f else 1f
            dockContainer.scaleY = if (prefs.dockHideStyle == "genie") 0.4f else 1f
            dockContainer.animate().translationY(0f).translationX(0f).alpha(1f).scaleX(1f).scaleY(1f).setDuration(duration)
                .setInterpolator(DecelerateInterpolator(1.6f)).start()
        } else {
            dockContainer.animate().translationY(hideY).translationX(hideX).alpha(0f)
                .scaleX(if (prefs.dockHideStyle == "genie") 0.4f else 1f)
                .scaleY(if (prefs.dockHideStyle == "genie") 0.4f else 1f)
                .setDuration(duration)
                .setInterpolator(DecelerateInterpolator(1.4f)).start()
        }
    }

    private fun scaleHomeForOpen(opening: Boolean, duration: Long = 260L) {
        homeContent.animate().cancel()
        if (opening) {
            homeContent.animate().scaleX(0.88f).scaleY(0.88f).alpha(0.72f).setDuration(duration).start()
        } else {
            homeContent.animate().scaleX(1f).scaleY(1f).alpha(1f).setDuration(duration).start()
        }
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
            when {
                prefs.dockStyle == "transparent" -> { d.setColor(0x00000000); d.setStroke(0, 0) }
                prefs.dockStyle == "solid" -> { d.setColor(if (prefs.lightMode) 0xCCFFFFFF.toInt() else 0xCC222222.toInt()) }
                prefs.liquidGlass || prefs.dockStyle == "glass" -> {
                    d.setColor(if (prefs.lightMode) 0x55FFFFFF else 0x44FFFFFF)
                    d.setStroke((1 * resources.displayMetrics.density).toInt(), 0x66FFFFFF)
                }
                else -> { d.setColor(if (prefs.lightMode) 0x66FFFFFF else 0x66000000); d.setStroke((1 * resources.displayMetrics.density).toInt(), 0x33FFFFFF) }
            }
            dockBar.background = d
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) dockBar.setRenderEffect(null)
        } catch (_: Exception) {}
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
