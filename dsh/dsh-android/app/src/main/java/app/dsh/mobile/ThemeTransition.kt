package app.dsh.mobile

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView

/**
 * 主题切换过渡：切换前抓取旧主题页面快照，重建后覆盖在新页面上淡出。
 */
object ThemeTransition {
    private var snapshot: Bitmap? = null

    fun capture(activity: Activity) {
        val view = activity.window?.decorView ?: return
        if (view.width <= 0 || view.height <= 0) return
        runCatching {
            val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
            view.draw(Canvas(bitmap))
            snapshot = bitmap
        }
    }

    fun reveal(activity: Activity) {
        val bitmap = snapshot ?: return
        snapshot = null
        val parent = activity.window?.decorView as? ViewGroup ?: return
        val overlay = ImageView(activity).apply {
            setImageBitmap(bitmap)
            scaleType = ImageView.ScaleType.FIT_XY
            isClickable = false
            alpha = 1f
        }
        parent.addView(
            overlay,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            ),
        )
        overlay.post {
            overlay.animate()
                .alpha(0f)
                .setDuration(400)
                .setInterpolator(android.view.animation.LinearInterpolator())
                .withEndAction {
                    parent.removeView(overlay)
                    runCatching { bitmap.recycle() }
                }
                .start()
        }
    }

    fun clear() {
        snapshot = null
    }
}