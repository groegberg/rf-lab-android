package dev.rflab.scanner

import android.app.Activity
import android.view.View
import android.view.WindowInsets

/**
 * Android 15+ forces edge-to-edge layouts when targetSdk >= 35.
 * Reserve the true status/navigation bar AND cutout insets for controls,
 * preserving each screen's original spacing. Never stack padding on every
 * dispatch (rotation, immersive transitions and gesture mode all re-dispatch).
 *
 * Camera/3D surfaces fill the remaining safe viewport rather than hiding
 * header buttons under status icons or footer buttons behind gesture nav.
 */
internal object SystemBarsSafeArea {
    data class Padding(val left: Int, val top: Int, val right: Int, val bottom: Int) {
        fun plus(other: Padding): Padding = Padding(
            left + other.left, top + other.top,
            right + other.right, bottom + other.bottom)
    }

    fun install(activity: Activity, root: View) {
        // Explicitly own the content insets on all supported versions.
        activity.window.setDecorFitsSystemWindows(false)
        val base = Padding(root.paddingLeft, root.paddingTop,
            root.paddingRight, root.paddingBottom)
        root.setOnApplyWindowInsetsListener { view, insets ->
            val system = insets.getInsets(
                WindowInsets.Type.statusBars() or
                    WindowInsets.Type.navigationBars() or
                    WindowInsets.Type.displayCutout())
            val applied = base.plus(Padding(
                system.left, system.top, system.right, system.bottom))
            view.setPadding(applied.left, applied.top,
                applied.right, applied.bottom)
            // Keep the insets available to embedded platform views.
            insets
        }
        root.requestApplyInsets()
    }
}
