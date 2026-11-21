package com.vibe.launcher

import android.app.ActivityOptions
import android.content.ClipData
import android.content.ComponentName
import android.content.Intent
import android.os.Bundle
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.switchmaterial.SwitchMaterial
import kotlin.math.abs
import kotlin.math.max

class MainActivity : AppCompatActivity() {

    private lateinit var prefs: LauncherPrefs
    private lateinit var root: FrameLayout
    private lateinit var gridRecycler: RecyclerView
    private lateinit var dockBar: LinearLayout
    private lateinit var settingsButton: ImageButton
    private lateinit var settingsOverlay: FrameLayout
    private lateinit var flagshipSwitch: SwitchMaterial
    private lateinit var appDrawer: View
    private lateinit var drawerRecycler: RecyclerView

    private lateinit var gridAdapter: AppGridAdapter
    private lateinit var drawerAdapter: DrawerAdapter

    private val allApps = mutableListOf<AppEntry>()
    private val dockApps = arrayListOf<AppEntry?>(null, null, null, null)
    private var draggedKey: String? = null
    private var draggedFromDock: Int? = null
    private var draggedFromDrawer = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        prefs = LauncherPrefs(this)
        root = findViewById(R.id.root)
        gridRecycler = findViewById(R.id.gridRecycler)
        dockBar = findViewById(R.id.dockBar)
        settingsButton = findViewById(R.id.settingsButton)
        settingsOverlay = findViewById(R.id.settingsOverlay)
        flagshipSwitch = findViewById(R.id.flagshipSwitch)
        appDrawer = findViewById(R.id.appDrawer)
        drawerRecycler = findViewById(R.id.drawerRecycler)

        setupGrid()
        setupDrawer()
        setupDock()
        setupSettings()
        setupGestures()
        setupBack()
        loadApps()
    }

    override fun onResume() {
        super.onResume()
        settingsButton.isVisible = false
        settingsOverlay.isVisible = false
        if (draggedKey == null) appDrawer.isVisible = false
    }

    private fun setupGrid() {
        gridAdapter = AppGridAdapter(
            onClick = { app, icon -> launchApp(app, icon) },
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
            onClick = { app, icon -> launchApp(app, icon) },
            onLongPress = { app, view -> startIconDrag(app, view, fromDock = null, fromDrawer = true) }
        )
        drawerRecycler.layoutManager = LinearLayoutManager(this)
        drawerRecycler.adapter = drawerAdapter
        appDrawer.setOnClickListener { /* consume */ }
    }

    private fun setupDock() {
        dockBar.setOnDragListener { _, event -> handleDockDrag(event) }
        renderDock()
    }

    private fun setupSettings() {
        flagshipSwitch.isChecked = prefs.flagshipAnimations
        flagshipSwitch.setOnCheckedChangeListener { _, checked ->
            prefs.flagshipAnimations = checked
        }
        settingsButton.setOnClickListener {
            settingsOverlay.isVisible = true
            settingsButton.isVisible = false
        }
        settingsOverlay.setOnClickListener { settingsOverlay.isVisible = false }
        findViewById<View>(R.id.settingsPanel).setOnClickListener { /* consume */ }
    }

    private fun setupGestures() {
        val swipeDetector = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onFling(e1: MotionEvent?, e2: MotionEvent, velocityX: Float, velocityY: Float): Boolean {
                if (e1 == null) return false
                val dy = e2.y - e1.y
                val dx = e2.x - e1.x
                if (abs(dy) > abs(dx) && dy < -120 && abs(velocityY) > 200) {
                    if (!appDrawer.isVisible && !settingsOverlay.isVisible) {
                        openDrawer()
                        return true
                    }
                }
                if (abs(dy) > abs(dx) && dy > 120 && abs(velocityY) > 200) {
                    if (appDrawer.isVisible) {
                        closeDrawer()
                        return true
                    }
                }
                return false
            }
        })

        root.setOnTouchListener { _, event ->
            swipeDetector.onTouchEvent(event)
            false
        }
        // Also catch swipes on home content area
        findViewById<View>(R.id.homeContent).setOnTouchListener { _, event ->
            swipeDetector.onTouchEvent(event)
            false
        }
    }

    private fun setupBack() {
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                when {
                    settingsOverlay.isVisible -> settingsOverlay.isVisible = false
                    settingsButton.isVisible -> settingsButton.isVisible = false
                    appDrawer.isVisible -> closeDrawer()
                    else -> {
                        // Home launcher: do nothing
                    }
                }
            }
        })
    }

    private fun openDrawer() {
        settingsButton.isVisible = false
        appDrawer.translationY = appDrawer.height.toFloat().takeIf { it > 0 } ?: 1200f
        appDrawer.isVisible = true
        appDrawer.animate().translationY(0f).setDuration(260).start()
    }

    private fun closeDrawer() {
        val h = appDrawer.height.toFloat().takeIf { it > 0 } ?: 1200f
        appDrawer.animate().translationY(h).setDuration(220).withEndAction {
            appDrawer.isVisible = false
            appDrawer.translationY = 0f
        }.start()
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
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val resolved = packageManager.queryIntentActivities(intent, 0)
        val apps = resolved.mapNotNull { ri ->
            val label = ri.loadLabel(packageManager)?.toString()?.trim().orEmpty()
            val pkg = ri.activityInfo?.packageName ?: return@mapNotNull null
            val act = ri.activityInfo?.name ?: return@mapNotNull null
            val icon = ri.loadIcon(packageManager) ?: return@mapNotNull null
            if (pkg == packageName) return@mapNotNull null
            AppEntry(label, pkg, act, icon)
        }.distinctBy { it.key }.sortedBy { it.label.lowercase() }

        allApps.clear()
        allApps.addAll(apps)

        val byKey = apps.associateBy { it.key }
        val savedDock = prefs.loadDockOrder().mapNotNull { byKey[it] }.distinctBy { it.key }.take(4)
        for (i in 0 until 4) dockApps[i] = savedDock.getOrNull(i)
        val used = dockApps.filterNotNull().map { it.key }.toMutableSet()

        val savedGrid = prefs.loadGridOrder().mapNotNull { byKey[it] }.distinctBy { it.key }
        val grid = (savedGrid.filter { it.key !in used } + apps.filter { it.key !in used && it.key !in savedGrid.map { s -> s.key }.toSet() })
            .distinctBy { it.key }
            .take(24)

        gridAdapter.submitApps(grid)
        drawerAdapter.submit(allApps)
        renderDock()
        persistLayout()
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
            item.layoutParams = LinearLayout.LayoutParams(itemWidth, LinearLayout.LayoutParams.MATCH_PARENT)
            if (app != null) {
                icon.setImageDrawable(app.icon)
                label.text = app.label
                item.setOnClickListener { launchApp(app, icon) }
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
        try {
            view.startDragAndDrop(data, shadow, app.key, 0)
            view.alpha = 0.35f
            if (fromDrawer) {
                // Hide drawer so user can drop onto home screen
                appDrawer.animate().cancel()
                appDrawer.isVisible = false
            }
        } catch (_: Exception) {
            draggedKey = null
            draggedFromDock = null
            draggedFromDrawer = false
            return false
        }
        return true
    }

    private fun handleGridDrag(event: android.view.DragEvent): Boolean {
        when (event.action) {
            android.view.DragEvent.ACTION_DRAG_ENDED -> {
                resetDragState()
            }
            android.view.DragEvent.ACTION_DROP -> {
                val key = event.clipData?.getItemAt(0)?.text?.toString() ?: return true
                val app = findApp(key) ?: return true
                val fromDock = draggedFromDock
                val fromDrawer = draggedFromDrawer
                if (fromDock != null) {
                    dockApps[fromDock] = null
                } else if (!fromDrawer) {
                    gridAdapter.removeByKey(key)
                }
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
            }
        }
        return true
    }

    private fun handleDockDrag(event: android.view.DragEvent): Boolean {
        if (event.action == android.view.DragEvent.ACTION_DRAG_ENDED) resetDragState()
        return true
    }

    private fun handleDockSlotDrag(slot: Int, event: android.view.DragEvent): Boolean {
        when (event.action) {
            android.view.DragEvent.ACTION_DROP -> {
                val key = event.clipData?.getItemAt(0)?.text?.toString() ?: return true
                val app = findApp(key) ?: return true
                val fromDock = draggedFromDock
                val fromDrawer = draggedFromDrawer
                val replaced = dockApps[slot]
                if (fromDock != null) {
                    dockApps[fromDock] = replaced
                } else if (!fromDrawer) {
                    gridAdapter.removeByKey(key)
                    if (replaced != null && gridAdapter.itemCount < 24) {
                        gridAdapter.insertAt(gridAdapter.itemCount, replaced)
                    }
                } else {
                    if (replaced != null && gridAdapter.itemCount < 24) {
                        gridAdapter.insertAt(gridAdapter.itemCount, replaced)
                    }
                }
                dockApps[slot] = app
                renderDock()
                persistLayout()
            }
            android.view.DragEvent.ACTION_DRAG_ENDED -> resetDragState()
        }
        return true
    }

    private fun placeInDockOrDrop(app: AppEntry) {
        val empty = dockApps.indexOfFirst { it == null }
        if (empty >= 0) dockApps[empty] = app
    }

    private fun findApp(key: String): AppEntry? {
        return allApps.firstOrNull { it.key == key }
    }

    private fun resetDragState() {
        for (i in 0 until gridRecycler.childCount) {
            gridRecycler.getChildAt(i)?.alpha = 1f
        }
        for (i in 0 until dockBar.childCount) {
            dockBar.getChildAt(i)?.alpha = 1f
        }
        // If drag came from drawer and wasn't dropped onto home, reopen drawer
        if (draggedFromDrawer && draggedKey != null) {
            // Check if app was placed on home/dock; if not, reopen drawer
            val key = draggedKey
            val onHome = gridAdapter.all().any { it.key == key } || dockApps.filterNotNull().any { it.key == key }
            if (!onHome) {
                appDrawer.isVisible = true
                appDrawer.translationY = 0f
            } else {
                appDrawer.isVisible = false
                appDrawer.translationY = 0f
            }
        }
        draggedKey = null
        draggedFromDock = null
        draggedFromDrawer = false
        renderDock()
    }

    private fun persistLayout() {
        prefs.saveGridOrder(gridAdapter.all().map { it.key })
        prefs.saveDockOrder(dockApps.mapNotNull { it?.key })
    }

    private fun launchApp(app: AppEntry, iconView: View) {
        val intent = Intent(Intent.ACTION_MAIN)
            .addCategory(Intent.CATEGORY_LAUNCHER)
            .setComponent(ComponentName(app.packageName, app.activityName))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

        val options = if (prefs.flagshipAnimations) {
            ActivityOptions.makeScaleUpAnimation(iconView, 0, 0, max(1, iconView.width), max(1, iconView.height))
        } else {
            ActivityOptions.makeCustomAnimation(this, android.R.anim.fade_in, android.R.anim.fade_out)
        }
        try {
            startActivity(intent, options.toBundle())
        } catch (_: Exception) {
        }
    }
}
