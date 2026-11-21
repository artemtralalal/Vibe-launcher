package com.vibe.launcher

import android.app.ActivityOptions
import android.content.ClipData
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
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.graphics.drawable.toBitmap
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
    private lateinit var settingsOverlay: FrameLayout
    private lateinit var flagshipSwitch: SwitchMaterial
    private lateinit var blurSwitch: SwitchMaterial
    private lateinit var appDrawer: View
    private lateinit var drawerRecycler: RecyclerView
    private lateinit var animationOverlay: View
    private lateinit var iconPackLabel: TextView

    private lateinit var gridAdapter: AppGridAdapter
    private lateinit var drawerAdapter: DrawerAdapter
    private lateinit var iconPackManager: IconPackManager

    private val allApps = mutableListOf<AppEntry>()
    private val dockApps = arrayListOf<AppEntry?>(null, null, null, null)
    private var draggedKey: String? = null
    private var draggedFromDock: Int? = null
    private var draggedFromDrawer = false

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
        settingsOverlay = findViewById(R.id.settingsOverlay)
        flagshipSwitch = findViewById(R.id.flagshipSwitch)
        blurSwitch = findViewById(R.id.blurSwitch)
        appDrawer = findViewById(R.id.appDrawer)
        drawerRecycler = findViewById(R.id.drawerRecycler)
        animationOverlay = findViewById(R.id.animationOverlay)
        iconPackLabel = findViewById(R.id.iconPackLabel)

        setupGrid()
        setupDrawer()
        setupDock()
        setupSettings()
        setupGestures()
        setupBack()
        loadApps()
        setupWelcome()
    }

    override fun onResume() {
        super.onResume()
        settingsButton.isVisible = false
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
            onLongPressDrag = { app, view -> startIconDrag(app, view, fromDock = null, fromDrawer = false) },
            onEmptyLongPress = { showSettingsButton() }
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
            onLongPress = { app, view -> startIconDrag(app, view, fromDock = null, fromDrawer = true) }
        )
        drawerRecycler.layoutManager = LinearLayoutManager(this)
        drawerRecycler.adapter = drawerAdapter
        appDrawer.setOnClickListener { }
    }

    private fun setupDock() {
        dockBar.setOnDragListener { _, event -> handleDockDrag(event) }
        renderDock()
    }

    private fun setupSettings() {
        flagshipSwitch.isChecked = prefs.flagshipAnimations
        flagshipSwitch.setOnCheckedChangeListener { _, c -> prefs.flagshipAnimations = c }
        blurSwitch.isChecked = prefs.blurEnabled
        blurSwitch.setOnCheckedChangeListener { _, c -> prefs.blurEnabled = c }

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

        settingsButton.setOnClickListener {
            settingsOverlay.isVisible = true
            settingsButton.isVisible = false
        }
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
                    settingsButton.isVisible -> settingsButton.isVisible = false
                    appDrawer.isVisible -> closeDrawer()
                    else -> {}
                }
            }
        })
    }

    private fun openDrawer() {
        settingsButton.isVisible = false
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
        start.setOnClickListener {
            prefs.welcomeDone = true
            overlay.animate().alpha(0f).setDuration(250).withEndAction { overlay.isVisible = false; overlay.alpha = 1f }.start()
        }
    }

    private fun showSettingsButton() {
        if (settingsOverlay.isVisible || appDrawer.isVisible) return
        settingsButton.alpha = 0f
        settingsButton.scaleX = 0.8f
        settingsButton.scaleY = 0.8f
        settingsButton.isVisible = true
        settingsButton.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(180).start()
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
            val savedDock = prefs.loadDockOrder().mapNotNull { byKey[it] }.distinctBy { it.key }.take(4)
            for (i in 0 until 4) dockApps[i] = savedDock.getOrNull(i)
            val used = dockApps.filterNotNull().map { it.key }.toMutableSet()
            val savedGridKeys = prefs.loadGridOrder().toSet()
            val savedGrid = prefs.loadGridOrder().mapNotNull { byKey[it] }.filter { it.key !in used }
            val grid = (savedGrid + apps.filter { it.key !in used && it.key !in savedGridKeys })
                .distinctBy { it.key }.take(24)

            gridAdapter.submitApps(grid)
            drawerAdapter.submit(allApps)
            renderDock()
            persistLayout()
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
                item.setOnClickListener { launchApp(app, icon, fromDrawer = false) }
                item.setOnLongClickListener { startIconDrag(app, item, fromDock = i, fromDrawer = false) }
                item.alpha = 1f
            } else {
                item.alpha = 0f
            }
            item.setOnDragListener { _, event -> handleDockSlotDrag(i, event) }
            dockBar.addView(item)
        }
    }

    private fun startIconDrag(app: AppEntry, view: View, fromDock: Int?, fromDrawer: Boolean): Boolean {
        draggedKey = app.key
        draggedFromDock = fromDock
        draggedFromDrawer = fromDrawer
        settingsButton.isVisible = false
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
            draggedKey = null; draggedFromDock = null; draggedFromDrawer = false
            false
        }
    }

    private fun handleGridDrag(event: android.view.DragEvent): Boolean {
        when (event.action) {
            android.view.DragEvent.ACTION_DRAG_ENDED -> resetDragState()
            android.view.DragEvent.ACTION_DROP -> try {
                val key = event.clipData?.getItemAt(0)?.text?.toString() ?: return true
                val app = findApp(key) ?: return true
                val fromDock = draggedFromDock
                val fromDrawer = draggedFromDrawer
                if (fromDock != null) dockApps[fromDock] = null else if (!fromDrawer) gridAdapter.removeByKey(key)
                val child = gridRecycler.findChildViewUnder(event.x, event.y)
                val insertAt = if (child != null) gridRecycler.getChildAdapterPosition(child) else gridAdapter.itemCount
                if (gridAdapter.itemCount >= 24 && gridAdapter.all().none { it.key == app.key }) {
                    val overflow = gridAdapter.all().lastOrNull()
                    if (overflow != null) {
                        gridAdapter.removeByKey(overflow.key)
                        placeInDockOrDrop(overflow)
                    }
                }
                gridAdapter.insertAt(insertAt.coerceAtLeast(0), app)
                renderDock()
                persistLayout()
            } catch (_: Exception) { resetDragState() }
        }
        return true
    }

    private fun handleDockDrag(event: android.view.DragEvent): Boolean {
        if (event.action == android.view.DragEvent.ACTION_DRAG_ENDED) resetDragState()
        return true
    }

    private fun handleDockSlotDrag(slot: Int, event: android.view.DragEvent): Boolean {
        when (event.action) {
            android.view.DragEvent.ACTION_DROP -> try {
                val key = event.clipData?.getItemAt(0)?.text?.toString() ?: return true
                val app = findApp(key) ?: return true
                val fromDock = draggedFromDock
                val fromDrawer = draggedFromDrawer
                val replaced = dockApps[slot]
                if (fromDock != null) dockApps[fromDock] = replaced
                else if (!fromDrawer) {
                    gridAdapter.removeByKey(key)
                    if (replaced != null && gridAdapter.itemCount < 24) gridAdapter.insertAt(gridAdapter.itemCount, replaced)
                } else if (replaced != null && gridAdapter.itemCount < 24) {
                    gridAdapter.insertAt(gridAdapter.itemCount, replaced)
                }
                dockApps[slot] = app
                renderDock()
                persistLayout()
            } catch (_: Exception) { resetDragState() }
            android.view.DragEvent.ACTION_DRAG_ENDED -> resetDragState()
        }
        return true
    }

    private fun placeInDockOrDrop(app: AppEntry) {
        val empty = dockApps.indexOfFirst { it == null }
        if (empty >= 0) dockApps[empty] = app
    }

    private fun findApp(key: String): AppEntry? = allApps.firstOrNull { it.key == key }

    private fun resetDragState() {
        if (draggedKey == null && !draggedFromDrawer) {
            for (i in 0 until gridRecycler.childCount) gridRecycler.getChildAt(i)?.alpha = 1f
            for (i in 0 until dockBar.childCount) dockBar.getChildAt(i)?.alpha = 1f
            return
        }
        try {
            for (i in 0 until gridRecycler.childCount) gridRecycler.getChildAt(i)?.alpha = 1f
            for (i in 0 until dockBar.childCount) dockBar.getChildAt(i)?.alpha = 1f
            if (draggedFromDrawer && draggedKey != null) {
                val key = draggedKey
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
        } catch (_: Exception) { }
        draggedKey = null; draggedFromDock = null; draggedFromDrawer = false
        try { renderDock() } catch (_: Exception) { }
    }

    private fun persistLayout() {
        prefs.saveGridOrder(gridAdapter.all().map { it.key })
        prefs.saveDockOrder(dockApps.mapNotNull { it?.key })
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
            .alpha(0.95f)
            .setDuration(prefs.closeDuration.toLong())
            .setInterpolator(DecelerateInterpolator())
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
