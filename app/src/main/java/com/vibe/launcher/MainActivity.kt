package com.vibe.launcher

import android.app.ActivityOptions
import android.content.ClipData
import android.content.ComponentName
import android.content.Intent
import android.graphics.Rect
import android.os.Bundle
import android.view.DragEvent
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import androidx.core.widget.doAfterTextChanged
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.switchmaterial.SwitchMaterial
import kotlin.math.max

class MainActivity : AppCompatActivity() {

    private lateinit var prefs: LauncherPrefs
    private lateinit var gridRecycler: RecyclerView
    private lateinit var dockBar: LinearLayout
    private lateinit var searchInput: EditText
    private lateinit var settingsButton: ImageButton
    private lateinit var settingsOverlay: FrameLayout
    private lateinit var flagshipSwitch: SwitchMaterial
    private lateinit var homeContent: View

    private lateinit var adapter: AppGridAdapter
    private val allApps = mutableListOf<AppEntry>()
    private val dockApps = mutableListOf<AppEntry?>()
    private var draggedKey: String? = null
    private var draggedFromDock: Int? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        prefs = LauncherPrefs(this)
        gridRecycler = findViewById(R.id.gridRecycler)
        dockBar = findViewById(R.id.dockBar)
        searchInput = findViewById(R.id.searchInput)
        settingsButton = findViewById(R.id.settingsButton)
        settingsOverlay = findViewById(R.id.settingsOverlay)
        flagshipSwitch = findViewById(R.id.flagshipSwitch)
        homeContent = findViewById(R.id.homeContent)

        setupGrid()
        setupDock()
        setupSearch()
        setupSettings()
        setupEmptyLongPress()
        loadApps()
    }

    override fun onResume() {
        super.onResume()
        settingsButton.isVisible = false
        settingsOverlay.isVisible = false
    }

    private fun setupGrid() {
        adapter = AppGridAdapter(
            onClick = { app, icon -> launchApp(app, icon) },
            onLongPressDrag = { app, view -> startIconDrag(app, view, fromDock = null) },
            onEmptyLongPress = { showSettingsButton() }
        )
        gridRecycler.layoutManager = GridLayoutManager(this, 4)
        gridRecycler.adapter = adapter
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

    private fun setupDock() {
        repeat(4) { dockApps.add(null) }
        dockBar.setOnDragListener { _, event -> handleDockDrag(event) }
        renderDock()
    }

    private fun setupSearch() {
        searchInput.doAfterTextChanged { text ->
            val q = text?.toString().orEmpty()
            adapter.filter(q)
            renderDock(q)
        }
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
        settingsOverlay.setOnClickListener {
            settingsOverlay.isVisible = false
        }
        findViewById<View>(R.id.settingsPanel).setOnClickListener { /* consume */ }
    }

    private fun setupEmptyLongPress() {
        homeContent.setOnLongClickListener {
            showSettingsButton()
            true
        }
    }

    private fun showSettingsButton() {
        if (settingsOverlay.isVisible) return
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
        }.sortedBy { it.label.lowercase() }

        allApps.clear()
        allApps.addAll(apps)

        val byKey = apps.associateBy { it.key }
        val savedDock = prefs.loadDockOrder().mapNotNull { byKey[it] }.distinctBy { it.key }.take(4)
        val savedGrid = prefs.loadGridOrder().mapNotNull { byKey[it] }.distinctBy { it.key }

        for (i in 0 until 4) dockApps[i] = savedDock.getOrNull(i)
        val used = dockApps.filterNotNull().map { it.key }.toSet()
        val grid = (savedGrid.filter { it.key !in used } + apps.filter { it.key !in used && it.key !in savedGrid.map { s -> s.key } })
            .distinctBy { it.key }
            .take(24)

        adapter.submitApps(grid)
        renderDock()
        persistLayout()
    }

    private fun renderDock(filter: String = searchInput.text?.toString().orEmpty()) {
        dockBar.removeAllViews()
        val q = filter.trim().lowercase()
        val density = resources.displayMetrics.density
        val itemWidth = ((resources.displayMetrics.widthPixels - density * 56) / 4).toInt()
        for (i in 0 until 4) {
            val app = dockApps[i]
            val visible = app != null && (q.isEmpty() || app.label.lowercase().contains(q))
            val item = layoutInflater.inflate(R.layout.item_app, dockBar, false)
            val icon = item.findViewById<ImageView>(R.id.appIcon)
            val label = item.findViewById<TextView>(R.id.appLabel)
            val lp = LinearLayout.LayoutParams(itemWidth, LinearLayout.LayoutParams.MATCH_PARENT)
            item.layoutParams = lp
            if (visible && app != null) {
                icon.setImageDrawable(app.icon)
                label.text = app.label
                item.setOnClickListener { launchApp(app, icon) }
                item.setOnLongClickListener { startIconDrag(app, item, fromDock = i) }
            } else {
                item.alpha = if (app == null || q.isEmpty()) 0f else 0.2f
                if (app != null) {
                    icon.setImageDrawable(app.icon)
                    label.text = app.label
                }
            }
            item.setOnDragListener { _, event -> handleDockSlotDrag(i, event) }
            dockBar.addView(item)
        }
    }

    private fun startIconDrag(app: AppEntry, view: View, fromDock: Int?): Boolean {
        draggedKey = app.key
        draggedFromDock = fromDock
        settingsButton.isVisible = false
        val data = ClipData.newPlainText("app", app.key)
        val shadow = View.DragShadowBuilder(view)
        view.startDragAndDrop(data, shadow, app.key, 0)
        view.alpha = 0.35f
        return true
    }

    private fun handleGridDrag(event: DragEvent): Boolean {
        when (event.action) {
            DragEvent.ACTION_DRAG_ENDED -> {
                resetDraggedAlpha()
                draggedKey = null
                draggedFromDock = null
            }
            DragEvent.ACTION_DROP -> {
                val key = event.clipData?.getItemAt(0)?.text?.toString() ?: return true
                val app = findApp(key) ?: return true
                val pos = gridRecycler.getChildAdapterPosition(gridRecycler.findChildViewUnder(event.x, event.y) ?: return true)
                val fromDock = draggedFromDock
                if (fromDock != null) {
                    dockApps[fromDock] = null
                } else {
                    adapter.removeByKey(key)
                }
                val insertAt = if (pos >= 0) pos else adapter.itemCount
                if (adapter.itemCount >= 24) {
                    val overflow = adapter.all().lastOrNull()
                    if (overflow != null && overflow.key != app.key) {
                        adapter.removeByKey(overflow.key)
                        placeInDockOrDrop(overflow)
                    }
                }
                adapter.insertAt(insertAt, app)
                renderDock()
                persistLayout()
            }
        }
        return true
    }

    private fun handleDockDrag(event: DragEvent): Boolean {
        if (event.action == DragEvent.ACTION_DRAG_ENDED) {
            resetDraggedAlpha()
            draggedKey = null
            draggedFromDock = null
        }
        return true
    }

    private fun handleDockSlotDrag(slot: Int, event: DragEvent): Boolean {
        when (event.action) {
            DragEvent.ACTION_DROP -> {
                val key = event.clipData?.getItemAt(0)?.text?.toString() ?: return true
                val app = findApp(key) ?: return true
                val fromDock = draggedFromDock
                val replaced = dockApps[slot]
                if (fromDock != null) {
                    dockApps[fromDock] = replaced
                } else {
                    adapter.removeByKey(key)
                    if (replaced != null && adapter.itemCount < 24) adapter.insertAt(adapter.itemCount, replaced)
                }
                dockApps[slot] = app
                renderDock()
                persistLayout()
            }
            DragEvent.ACTION_DRAG_ENDED -> {
                resetDraggedAlpha()
                draggedKey = null
                draggedFromDock = null
            }
        }
        return true
    }

    private fun placeInDockOrDrop(app: AppEntry) {
        val empty = dockApps.indexOfFirst { it == null }
        if (empty >= 0) dockApps[empty] = app
    }

    private fun findApp(key: String): AppEntry? {
        return allApps.firstOrNull { it.key == key }
            ?: adapter.all().firstOrNull { it.key == key }
            ?: dockApps.filterNotNull().firstOrNull { it.key == key }
    }

    private fun resetDraggedAlpha() {
        for (i in 0 until gridRecycler.childCount) {
            gridRecycler.getChildAt(i)?.alpha = 1f
        }
        for (i in 0 until dockBar.childCount) {
            dockBar.getChildAt(i)?.alpha = 1f
        }
        renderDock()
    }

    private fun persistLayout() {
        prefs.saveGridOrder(adapter.all().map { it.key })
        prefs.saveDockOrder(dockApps.mapNotNull { it?.key })
    }

    private fun launchApp(app: AppEntry, iconView: View) {
        val intent = Intent(Intent.ACTION_MAIN)
            .addCategory(Intent.CATEGORY_LAUNCHER)
            .setComponent(ComponentName(app.packageName, app.activityName))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

        val options = if (prefs.flagshipAnimations) {
            ActivityOptions.makeScaleUpAnimation(iconView, 0, 0, iconView.width, iconView.height)
        } else {
            val cx = max(1, iconView.width / 2)
            val cy = max(1, iconView.height / 4)
            ActivityOptions.makeScaleUpAnimation(iconView, cx, cy, 1, 1)
        }
        try {
            startActivity(intent, options.toBundle())
        } catch (_: Exception) {
            // App may be unavailable; ignore for MVP.
        }
    }
}
