package com.vibe.launcher

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import org.xmlpull.v1.XmlPullParser

class IconPackManager(private val context: Context) {
    fun getInstalledIconPacks(): List<Pair<String, String>> {
        val pm = context.packageManager
        val intents = listOf(
            Intent("org.adw.launcher.THEMES"),
            Intent("com.gau.go.launcherex.theme"),
            Intent("com.anddoes.launcher.THEME"),
            Intent("com.teslacoilsw.launcher.THEME")
        )
        val packs = linkedMapOf<String, String>()
        for (i in intents) {
            val list = pm.queryIntentActivities(i, 0)
            for (ri in list) {
                val pkg = ri.activityInfo.packageName
                val label = ri.loadLabel(pm)?.toString() ?: pkg
                packs[pkg] = label
            }
        }
        return packs.map { it.key to it.value }.sortedBy { it.second.lowercase() }
    }

    fun loadIcon(packPackage: String, component: String, defaultIcon: Drawable): Drawable {
        if (packPackage.isBlank()) return defaultIcon
        return try {
            val pm = context.packageManager
            val res = pm.getResourcesForApplication(packPackage)
            val id = findDrawableId(res, packPackage, component)
            if (id != 0) res.getDrawable(id, null) ?: defaultIcon else defaultIcon
        } catch (_: Exception) {
            defaultIcon
        }
    }

    private fun findDrawableId(res: android.content.res.Resources, pack: String, component: String): Int {
        // Try appfilter.xml
        try {
            val rawId = res.getIdentifier("appfilter", "xml", pack)
            if (rawId != 0) {
                val parser = res.getXml(rawId)
                var event = parser.eventType
                while (event != XmlPullParser.END_DOCUMENT) {
                    if (event == XmlPullParser.START_TAG && parser.name == "item") {
                        val comp = parser.getAttributeValue(null, "component") ?: ""
                        val drawable = parser.getAttributeValue(null, "drawable") ?: ""
                        if (comp.contains(component, ignoreCase = true) && drawable.isNotBlank()) {
                            val id = res.getIdentifier(drawable, "drawable", pack)
                            if (id != 0) return id
                        }
                    }
                    event = parser.next()
                }
            }
        } catch (_: Exception) { }
        // Fallback: guess by component name
        val guess = component.substringAfter("/").substringAfterLast(".").lowercase().replace("[^a-z0-9_]".toRegex(), "_")
        val id = res.getIdentifier(guess, "drawable", pack)
        return id
    }
}
