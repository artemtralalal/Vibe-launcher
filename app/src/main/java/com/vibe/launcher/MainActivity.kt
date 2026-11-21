package com.vibe.launcher

import android.app.ActivityOptions
import android.app.WallpaperManager
import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.Context
import android.content.IntentFilter
import android.content.ComponentName
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
    private lateinit var settingsButton: ImageButton
    private lateinit var quickPanel: View
    private lateinit var settingsOverlay: FrameLayout
    private lateinit var flagshipSwitch: SwitchMaterial
    private lateinit var blurSwitch: SwitchMaterial
    private lateinit var appDrawer: View
    private lateinit var drawerRecycler: RecyclerView
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
    private val pages = mutableListOf<MutableList<AppEntry>>()
    private var currentPageIndex = 0
    private val pageSize = 24

    private var draggedKey: String? = null
    private var draggedFromDock: Int? = null
    private var draggedFromDrawer = false
    private var draggedFromPage: Int = -1
    private var dragDropped = false
    private var pendingDropKey: String? = null
    private var pendingDropDockSlot: Int? = null
    private var pendingDropGridIndex: Int = -1
    private var pageSwitchRunnable: Runnable? = null

    private val unlockReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == Intent.ACTION_USER_PRESENT) playUnlockAnimation()
        }
    }

    private val wallpaperPickLauncher = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) showWallpaperTargetDialog(uri)
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        prefs = LauncherPrefs(this)
        iconPackManager = IconPackManager(this)
        root = findViewById(R.id.root)
        homeContent = findViewById(R.id.homeContent)
        gridRecycler = findViewById(R.id.gridRecycler)
        dockBar = findViewById(R.id.dockBar)
        settingsButton = findViewById(R.id.settingsButton)
        quickPanel = findViewById(R.id.quickPanel)
        settingsOverlay = findViewById(R.id.settingsOverlay)
        flagshipSwitch = findViewById(R.id.flagshipSwitch)
        blurSwitch = findViewById(R.id.blurSwitch)
        appDrawer = findViewById(R.id.appDrawer)
        drawerRecycler = findViewById(R.id.drawerRecycler)
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
        loadApps()
        setupWelcome()
        try { registerReceiver(unlockReceiver, IntentFilter(Intent.ACTION_USER_PRESENT)) } catch (_: Exception) {}
        try { registerReceiver(volumeReceiver, IntentFilter("android.media.VOLUME_CHANGED_ACTION")) } catch (_: Exception) {}
    }

    override fun onDestroy() {
        try { unregisterReceiver(unlockReceiver) } catch (_: Exception) {}
        try { unregisterReceiver(volumeReceiver) } catch (_: Exception) {}
        super.onDestroy()
    }

    override fun onResume() {
        super.onResume()
        quickPanel.isVisible = false; settingsButton.isVisible = false
        quickPanel.isVisible = false
        settingsOverlay.isVisible = false
        if (draggedKey == null) appDrawer.isVisible = false
        if (pendingLaunch) {
            pendingLaunch = false
            playCloseAnimation()
        } else {
            clearBlur()
        }
    }

    private fun setupGrid() {
        gridAdapter = AppGridAdapter(
            onClick = { app, icon -> launchApp(app, icon, fromDrawer = false) },
            onLongPressDrag = { app, view -> showIconMenu(app, view, fromDock = null, fromDrawer = false) },
            onEmptyLongPress = { showSettingsButton() },
            shape = { prefs.iconShape },
            showLabels = { prefs.showLabels },
            cornerRadii = { currentCornerRadii() }
        )
        gridRecycler.layoutManager = GridLayoutManager(this, 4)
        gridRecycler.adapter = gridAdapter
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
        drawerRecycler.layoutManager = GridLayoutManager(this, 4)
        drawerRecycler.adapter = drawerAdapter
        appDrawer.setOnClickListener { }
    }

    private fun setupDock() {
        dockBar.setOnDragListener { _, event -> handleDockDrag(event) }
        renderDock()
    }

    private fun ensurePages() {
        if (pages.isEmpty()) pages.add(mutableListOf())
    }

    private fun currentPageItems(): MutableList<AppEntry> {
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
            pageIndicator.addView(dot)
        }
        pageIndicator.isVisible = count > 1
    }

    private fun switchPage(delta: Int, fromDrag: Boolean = false) {
        ensurePages()
        val next = currentPageIndex + delta
        if (next < 0) return
        if (next > pages.lastIndex) {
            if (fromDrag) pages.add(mutableListOf()) else return
        }
        if (next == currentPageIndex) return
        pages[currentPageIndex] = gridAdapter.all().toMutableList()
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
        flagshipSwitch.isChecked = prefs.flagshipAnimations
        flagshipSwitch.setOnCheckedChangeListener { _, c -> prefs.flagshipAnimations = c }
        blurSwitch.isChecked = prefs.blurEnabled
        blurSwitch.setOnCheckedChangeListener { _, c -> prefs.blurEnabled = c }
        val labelsSwitch = findViewById<SwitchMaterial>(R.id.labelsSwitch)
        labelsSwitch.isChecked = prefs.showLabels
        labelsSwitch.setOnCheckedChangeListener { _, c -> prefs.showLabels = c; gridAdapter.notifyDataSetChanged() }

        bindSeek(findViewById(R.id.openDurationSeek), prefs.openDuration, 100, 700) { prefs.openDuration = it }
        bindSeek(findViewById(R.id.closeDurationSeek), prefs.closeDuration, 100, 700) { prefs.closeDuration = it }
        bindSeek(findViewById(R.id.openScaleSeek), (prefs.openScale * 100).toInt(), 20, 100) { prefs.openScale = it / 100f }
        bindSeek(findViewById(R.id.closeScaleSeek), (prefs.closeScale * 100).toInt(), 20, 100) { prefs.closeScale = it / 100f }
        bindSeek(findViewById(R.id.closeTySeek), (prefs.closeTranslateY + 100).toInt(), 0, 200) { prefs.closeTranslateY = (it - 100).toFloat() }
        bindSeek(findViewById(R.id.overshootSeek), (prefs.openOvershoot * 100).toInt(), 0, 150) {
            prefs.openOvershoot = it / 100f
            prefs.closeOvershoot = it / 120f
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
        updateUnlockLabel()
        findViewById<Button>(R.id.unlockAnimBtn).setOnClickListener { showUnlockAnimPicker() }
        updateDrawerStyleLabel()
        findViewById<Button>(R.id.drawerStyleBtn).setOnClickListener { showDrawerStylePicker() }

        settingsButton.setOnClickListener {
            settingsOverlay.isVisible = true
            quickPanel.isVisible = false; settingsButton.isVisible = false
            quickPanel.isVisible = false
        }
        findViewById<View>(R.id.quickWallpaper).setOnClickListener { quickPanel.isVisible = false; openWallpaperPicker() }
        findViewById<View>(R.id.quickSettings).setOnClickListener { quickPanel.isVisible = false; settingsOverlay.isVisible = true }
        findViewById<View>(R.id.quickIcons).setOnClickListener { quickPanel.isVisible = false; showIconShapePicker() }
        findViewById<View>(R.id.quickWidgets).setOnClickListener { quickPanel.isVisible = false; android.widget.Toast.makeText(this, "Виджеты скоро будут добавлены", android.widget.Toast.LENGTH_SHORT).show() }
        settingsOverlay.setOnClickListener { settingsOverlay.isVisible = false }
        findViewById<View>(R.id.settingsPanel).setOnClickListener { }
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
                loadApps()
            }.show()
    }

    private fun updateUnlockLabel() {
        val label = when (prefs.unlockAnim) {
            "pixel" -> "Pixel"; "poco" -> "POCO/Xiaomi"; "ios" -> "iPhone"
            "ripple" -> "Ripple (отпечаток)"; "wave" -> "Волна"; "fire" -> "Огонь"; "ice" -> "Лёд"; "none" -> "Нет"; else -> prefs.unlockAnim
        }
        findViewById<TextView>(R.id.unlockAnimLabel).text = "Анимация разблокировки: $label"
    }

    private fun showUnlockAnimPicker() {
        val opts = listOf(
            "pixel" to "Pixel", "poco" to "POCO/Xiaomi", "ios" to "iPhone",
            "ripple" to "Ripple (отпечаток)", "wave" to "Волна", "fire" to "Огонь", "ice" to "Лёд", "none" to "Нет"
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

    private fun setupGestures() {
        globalDetector = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
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
                    if (appDrawer.isVisible) { closeDrawer(); return true }
                }
                if (abs(dx) > abs(dy) && abs(dx) > 120 && abs(velocityX) > 200) {
                    if (!appDrawer.isVisible && !settingsOverlay.isVisible && draggedKey == null) {
                        if (dx < 0) switchPageAnimated(1) else switchPageAnimated(-1)
                        return true
                    }
                }
                return false
            }
        })
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        if (::globalDetector.isInitialized) globalDetector.onTouchEvent(ev)
        return super.dispatchTouchEvent(ev)
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
                    settingsOverlay.isVisible -> settingsOverlay.isVisible = false
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

        fun finishWelcome() {
            prefs.welcomeDone = true
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
                pages.add(mutableListOf())
            } else {
                rest.chunked(pageSize).forEach { chunk -> pages.add(chunk.toMutableList()) }
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
            pages.add(mutableListOf())
            currentPageIndex = 0
            renderCurrentPage()
            renderDock()
            persistLayout()
        } catch (_: Exception) { }
    }

    private fun playUnlockAnimation() {
        if (prefs.unlockAnim == "none") return
        try {
            val views = mutableListOf<View>()
            for (i in 0 until gridRecycler.childCount) gridRecycler.getChildAt(i)?.let { views.add(it) }
            for (i in 0 until dockBar.childCount) dockBar.getChildAt(i)?.let { views.add(it) }
            val centerX = root.width / 2f
            val bottomY = root.height.toFloat()
            val anim = prefs.unlockAnim

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

    private fun animateShatter(target: View, onEnd: () -> Unit) {
        try {
            val bmp = target.toBitmap()
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
        quickPanel.alpha = 0f
        quickPanel.scaleX = 0.92f
        quickPanel.scaleY = 0.92f
        quickPanel.isVisible = true
        quickPanel.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(180).start()
        quickPanel.isVisible = false; settingsButton.isVisible = false
    }

    private fun loadApps() {
        try {
            val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
            val resolved = packageManager.queryIntentActivities(intent, 0)
            val pack = prefs.iconPackPackage
            val apps = resolved.mapNotNull { ri ->
                try {
                    val label = ri.loadLabel(packageManager)?.toString()?.trim().orEmpty()
                    val pkg = ri.activityInfo?.packageName ?: return@mapNotNull null
                    val act = ri.activityInfo?.name ?: return@mapNotNull null
                    val defIcon = ri.loadIcon(packageManager) ?: return@mapNotNull null
                    if (pkg == packageName) return@mapNotNull null
                    val component = "$pkg/$act"
                    val icon = runCatching { iconPackManager.loadIcon(pack, component, defIcon) }.getOrDefault(defIcon)
                    AppEntry(label, pkg, act, icon)
                } catch (_: Exception) { null }
            }.distinctBy { it.key }.sortedBy { it.label.lowercase() }

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
                        val page = pageKeys.mapNotNull { byKey[it] }.filter { it.key !in used }.distinctBy { it.key }.toMutableList()
                        used.addAll(page.map { it.key })
                        pages.add(page)
                    }
                }
                if (pages.isEmpty()) pages.add(mutableListOf())
                // drop empty trailing pages but keep at least one
                while (pages.size > 1 && pages.last().isEmpty()) pages.removeAt(pages.lastIndex)
                currentPageIndex = prefs.currentPage.coerceIn(0, pages.lastIndex)
                renderCurrentPage()
            } else {
                for (i in 0 until 4) dockApps[i] = null
                pages.clear()
                pages.add(mutableListOf())
                currentPageIndex = 0
                renderCurrentPage()
            }
            drawerAdapter.submit(allApps)
            renderDock()
            if (prefs.layoutInitialized) persistLayout()
        } catch (_: Exception) { }
    }

    private fun renderDock() {
        dockBar.removeAllViews()
        val density = resources.displayMetrics.density
        val itemWidth = ((resources.displayMetrics.widthPixels - density * 56) / 4).toInt()
        for (i in 0 until 4) {
            val app = dockApps[i]
            val item = layoutInflater.inflate(R.layout.item_app, dockBar, false)
            val icon = item.findViewById<ImageView>(R.id.appIcon)
            val label = item.findViewById<TextView>(R.id.appLabel)
            label.visibility = View.GONE
            item.layoutParams = LinearLayout.LayoutParams(itemWidth, LinearLayout.LayoutParams.MATCH_PARENT)
            if (app != null) {
                icon.setImageDrawable(app.icon)
                label.text = app.label
                AppGridAdapter.applyShape(icon, prefs.iconShape, currentCornerRadii())
                item.setOnClickListener { launchApp(app, icon, fromDrawer = false) }
                item.setOnLongClickListener { showIconMenu(app, item, fromDock = i, fromDrawer = false) }
                item.alpha = 1f
            } else {
                item.alpha = 0f
            }
            item.setOnDragListener { _, event -> handleDockSlotDrag(i, event) }
            dockBar.addView(item)
        }
        val drawerBtn = findViewById<ImageButton>(R.id.drawerBtn)
        drawerBtn.isVisible = prefs.drawerStyle == "button"
        drawerBtn.setColorFilter(prefs.accentColor)
        drawerBtn.setOnClickListener { if (appDrawer.isVisible) closeDrawer() else openDrawer() }
    }

    private fun showIconMenu(app: AppEntry, view: View, fromDock: Int?, fromDrawer: Boolean): Boolean {
        val options = mutableListOf<String>()
        val actions = mutableListOf<() -> Unit>()
        options.add("Переместить"); actions.add {
            view.setOnTouchListener { v, ev ->
                if (ev.action == android.view.MotionEvent.ACTION_DOWN) {
                    v.setOnTouchListener(null)
                    startIconDrag(app, v, fromDock, fromDrawer)
                    true
                } else false
            }
            android.widget.Toast.makeText(this, "Теперь перетащите иконку", android.widget.Toast.LENGTH_SHORT).show()
        }
        if (!fromDrawer) {
            options.add("Убрать с рабочего стола"); actions.add {
                animateShatter(view) {
                    if (fromDock != null) dockApps[fromDock] = null else gridAdapter.removeByKey(app.key)
                    renderDock(); persistLayout(); updatePageIndicator()
                }
            }
        }
        options.add("Удалить с телефона"); actions.add {
            try {
                val intent = Intent(Intent.ACTION_DELETE, android.net.Uri.parse("package:${app.packageName}"))
                startActivity(intent)
            } catch (_: Exception) { }
        }
        AlertDialog.Builder(this).setTitle(app.label).setItems(options.toTypedArray()) { _, which -> actions[which]() }.show()
        return true
    }

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

    private fun handleGridDrag(event: android.view.DragEvent): Boolean {
        when (event.action) {
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
                val child = gridRecycler.findChildViewUnder(event.x, event.y)
                val insertAt = if (child != null) gridRecycler.getChildAdapterPosition(child) else gridAdapter.itemCount
                pendingDropKey = key
                pendingDropDockSlot = null
                pendingDropGridIndex = insertAt.coerceAtLeast(0)
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
                val idx = p.indexOfFirst { it.key == key }
                if (idx >= 0) p.removeAt(idx)
            }
        }
    }

    private fun applyPendingDropNow(key: String, toDock: Int?, toGrid: Int, fromDock: Int?, fromDrawer: Boolean, fromPage: Int) {
        val app = findApp(key) ?: return
        ensurePages()

        if (toDock != null) {
            if (fromDock == toDock) return
            val replaced = dockApps[toDock]
            if (fromDock != null) {
                dockApps[fromDock] = replaced
            } else if (!fromDrawer) {
                // remove from source page
                if (fromPage >= 0 && fromPage < pages.size && fromPage != currentPageIndex) {
                    pages[fromPage].removeAll { it.key == key }
                } else {
                    gridAdapter.removeByKey(key)
                }
                if (replaced != null) addToCurrentPage(replaced, gridAdapter.itemCount)
            } else {
                if (replaced != null) addToCurrentPage(replaced, gridAdapter.itemCount)
            }
            dockApps[toDock] = app
        } else {
            if (fromDock != null) {
                dockApps[fromDock] = null
            } else if (!fromDrawer) {
                if (fromPage >= 0 && fromPage < pages.size && fromPage != currentPageIndex) {
                    pages[fromPage].removeAll { it.key == key }
                } else {
                    gridAdapter.removeByKey(key)
                }
            }
            // if app exists elsewhere on other pages, remove duplicates
            removeAppFromPages(key, exceptPage = currentPageIndex)
            addToCurrentPage(app, toGrid)
        }
    }

    private fun addToCurrentPage(app: AppEntry, index: Int) {
        ensurePages()
        // remove if already on current page (gridAdapter handles this but be safe)
        val cur = gridAdapter.all().toMutableList().apply { removeAll { it.key == app.key } }
        val insertAt = index.coerceIn(0, cur.size)
        cur.add(insertAt, app)
        // overflow to next page
        if (cur.size > pageSize) {
            val overflow = cur.removeAt(cur.lastIndex)
            pages[currentPageIndex] = cur
            gridAdapter.submitApps(cur.toList())
            placeOverflowOnNextPage(overflow)
        } else {
            pages[currentPageIndex] = cur
            gridAdapter.submitApps(cur.toList())
        }
    }

    private fun placeOverflowOnNextPage(app: AppEntry) {
        var target = currentPageIndex + 1
        if (target >= pages.size) pages.add(mutableListOf())
        // avoid duplicates
        pages.forEach { p -> p.removeAll { it.key == app.key } }
        if (pages[target].size >= pageSize) {
            val ov = pages[target].removeAt(pages[target].lastIndex)
            pages[target].add(0, app)
            if (target + 1 >= pages.size) pages.add(mutableListOf())
            pages[target + 1].add(0, ov)
        } else {
            pages[target].add(0, app)
        }
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
                if (dropped && pKey != null) {
                    applyPendingDropNow(pKey, pDock, pGrid, fromDock, fromDrawer, fromPage)
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
                if (dropped) persistLayout()
            } catch (_: Exception) { }
        }
    }

    private fun persistLayout() {
        ensurePages()
        pages[currentPageIndex] = gridAdapter.all().toMutableList()
        while (pages.size > 1 && pages.last().isEmpty()) pages.removeAt(pages.lastIndex)
        val clamped = currentPageIndex.coerceIn(0, pages.lastIndex)
        val changed = clamped != currentPageIndex
        currentPageIndex = clamped
        prefs.savePages(pages.map { p -> p.map { it.key } })
        prefs.currentPage = currentPageIndex
        prefs.saveDockOrder(dockApps.mapNotNull { it?.key })
        if (changed) renderCurrentPage() else updatePageIndicator()
    }

    private fun launchApp(app: AppEntry, iconView: View, fromDrawer: Boolean) {
        val intent = Intent(Intent.ACTION_MAIN)
            .addCategory(Intent.CATEGORY_LAUNCHER)
            .setComponent(ComponentName(app.packageName, app.activityName))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

        val loc = IntArray(2)
        iconView.getLocationOnScreen(loc)
        lastLaunchKey = app.key
        lastLaunchBounds = intArrayOf(loc[0], loc[1], iconView.width, iconView.height)
        lastLaunchColor = extractColor(app.icon)

        if (!prefs.flagshipAnimations) {
            val opts = ActivityOptions.makeCustomAnimation(this, android.R.anim.fade_in, android.R.anim.fade_out)
            try { startActivity(intent, opts.toBundle()) } catch (_: Exception) {}
            pendingLaunch = false
            return
        }

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

    private fun playOpenAnimation(fromDrawer: Boolean, bounds: IntArray, color: Int, onEnd: () -> Unit) {
        applyBlur(true)
        scaleHomeForOpen(true)

        val overlay = animationOverlay
        overlay.background = roundedBg(color)
        overlay.isVisible = true
        overlay.alpha = 0f

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
        overlay.alpha = 1f

        overlay.animate().cancel()
        overlay.animate()
            .scaleX(1f).scaleY(1f)
            .translationX(prefs.openTranslateX)
            .translationY(prefs.openTranslateY)
            .setDuration(prefs.openDuration.toLong())
            .setInterpolator(OvershootInterpolator(prefs.openOvershoot))
            .withEndAction { onEnd() }
            .start()
    }

    private fun playCloseAnimation() {
        val bounds = lastLaunchBounds ?: run { clearBlur(); scaleHomeForOpen(false); return }
        val overlay = animationOverlay
        val screenW = root.width.toFloat().coerceAtLeast(1f)
        val screenH = root.height.toFloat().coerceAtLeast(1f)
        val endW = bounds[2].toFloat().coerceAtLeast(1f)
        val endH = bounds[3].toFloat().coerceAtLeast(1f)
        val endCx = bounds[0] + endW / 2f
        val endCy = bounds[1] + endH / 2f
        val startCx = screenW / 2f
        val startCy = screenH / 2f

        val tX = endCx - startCx + prefs.closeTranslateX
        val tY = endCy - startCy + prefs.closeTranslateY
        val sX = (endW / screenW).coerceAtLeast(0.08f)
        val sY = (endH / screenH).coerceAtLeast(0.08f)

        overlay.isVisible = true
        overlay.alpha = 1f
        overlay.scaleX = 1f
        overlay.scaleY = 1f
        overlay.translationX = 0f
        overlay.translationY = 0f
        overlay.background = roundedBg(lastLaunchColor)

        overlay.animate().cancel()
        overlay.animate()
            .scaleX(sX).scaleY(sY)
            .translationX(tX).translationY(tY)
            .alpha(0.96f)
            .setDuration(prefs.closeDuration.toLong())
            .setInterpolator(DecelerateInterpolator(1.6f))
            .withEndAction {
                overlay.isVisible = false
                overlay.alpha = 1f
                overlay.scaleX = 1f
                overlay.scaleY = 1f
                overlay.translationX = 0f
                overlay.translationY = 0f
                clearBlur()
                scaleHomeForOpen(false)
                bounceTargetIcon(lastLaunchKey)
            }
            .start()
    }

    private fun bounceTargetIcon(key: String?) {
        if (key == null) return
        val view = findIconViewByKey(key) ?: return
        view.animate().cancel()
        view.scaleX = 0.85f
        view.scaleY = 0.85f
        view.animate().scaleX(1f).scaleY(1f).setDuration(220)
            .setInterpolator(OvershootInterpolator(1.4f)).start()
    }

    private fun findIconViewByKey(key: String): View? {
        for (i in 0 until gridRecycler.childCount) {
            val child = gridRecycler.getChildAt(i) ?: continue
            val pos = gridRecycler.getChildAdapterPosition(child)
            if (pos >= 0 && pos < gridAdapter.itemCount && gridAdapter.all().getOrNull(pos)?.key == key) return child
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
            if (enable && prefs.blurEnabled) {
                val r = prefs.blurRadius.coerceIn(1f, 60f)
                homeContent.setRenderEffect(RenderEffect.createBlurEffect(r, r, Shader.TileMode.CLAMP))
            } else {
                homeContent.setRenderEffect(null)
            }
        }
    }

    private fun clearBlur() = applyBlur(false)

    private fun scaleHomeForOpen(opening: Boolean) {
        homeContent.animate().cancel()
        if (opening) {
            homeContent.animate().scaleX(0.92f).scaleY(0.92f).alpha(0.85f).setDuration(260).start()
        } else {
            homeContent.animate().scaleX(1f).scaleY(1f).alpha(1f).setDuration(260).start()
        }
    }

    private fun roundedBg(color: Int): Drawable {
        val d = android.graphics.drawable.GradientDrawable()
        d.shape = android.graphics.drawable.GradientDrawable.RECTANGLE
        d.cornerRadius = 48f * resources.displayMetrics.density
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
