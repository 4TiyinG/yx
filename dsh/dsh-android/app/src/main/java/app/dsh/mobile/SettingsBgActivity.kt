package app.dsh.mobile

import android.content.Context
import android.content.Intent
import android.graphics.drawable.BitmapDrawable
import android.os.Bundle
import android.view.View
import android.widget.ImageView
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import app.dsh.mobile.ui.ImagePipeline

/**
 * 设置页背景图（v1.5.x）：选图、不透明度、模糊强度。
 *
 * 三项都实时反映在上方预览里；改动写偏好后，[ThemedActivity.applySettingsBackground]
 * 会在每个设置页的 onResume 套用，因此返回任意设置页立即看到效果。
 */
class SettingsBgActivity : ThemedActivity() {

    /** 属于设置体系：本页自身也套用背景图。 */
    override val usesSettingsBackground: Boolean get() = true

    private lateinit var imgPreview: ImageView
    private lateinit var txtHint: TextView
    private lateinit var seekAlpha: SeekBar
    private lateinit var seekBlur: SeekBar
    private lateinit var valAlpha: TextView
    private lateinit var valBlur: TextView

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(LocaleHelper.wrap(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        OrientationPrefs.applyToSettingsPage(this)
        setContentView(R.layout.activity_settings_bg)

        findViewById<ImageView>(R.id.btnBack).setOnClickListener { finish() }

        imgPreview = findViewById(R.id.imgBgPreview)
        txtHint = findViewById(R.id.txtPreviewHint)
        seekAlpha = findViewById(R.id.seekBgAlpha)
        seekBlur = findViewById(R.id.seekBgBlur)
        valAlpha = findViewById(R.id.valBgAlpha)
        valBlur = findViewById(R.id.valBgBlur)

        seekAlpha.progress = CustomVisualPref.settingsBgAlpha(this)
        seekBlur.progress = CustomVisualPref.settingsBgBlur(this)

        seekAlpha.setOnSeekBarChangeListener(object : SimpleSeek() {
            override fun changed(v: Int) {
                CustomVisualPref.setSettingsBgAlpha(this@SettingsBgActivity, v)
                refreshPreview()
                // 拖动过程中**不**重贴整页背景：alpha 只是 Drawable 的整数属性，
                // 等松手（stopped）时一次到位即可（v1.6.31）。
            }

            override fun stopped() {
                applySettingsBackground()
            }
        })
        seekBlur.setOnSeekBarChangeListener(object : SimpleSeek() {
            override fun changed(v: Int) {
                CustomVisualPref.setSettingsBgBlur(this@SettingsBgActivity, v)
                refreshPreview()
                // v1.6.31：拖动中不再逐档 applySettingsBackground()。
                //
                // 旧写法每移动一格就在主线程跑一次「解码 + 1080 宽真高斯模糊 +
                // 全树压暗卡片」，拖一次滑块 = 二十几次几百毫秒级卷积，
                // 拖动本身必然卡成幻灯片。现在预览走后台（去重合并），
                // 整页背景只在松手时算一次。
            }

            override fun stopped() {
                applySettingsBackground()
            }
        })

        findViewById<View>(R.id.btnPickBg).setOnClickListener { pickImage() }
        findViewById<View>(R.id.btnRemoveBg).setOnClickListener {
            CustomVisualPref.clearSettingsBg(this)
            applySettingsBackground()
            refreshPreview()
        }
    }

    override fun onResume() {
        super.onResume()
        OrientationPrefs.applyToSettingsPage(this)
        refreshPreview()
    }

    override fun onDestroy() {
        // 预览可能还在后台算：撤掉，别往销毁后的 ImageView 里贴图
        ImagePipeline.cancel(PREVIEW_TAG)
        super.onDestroy()
    }

    /**
     * 预览：与实机相同的一套处理（同一张盘上文件 + 同样的模糊与不透明度），
     * 做到「预览什么样，设置页就什么样」。
     *
     * v1.6.31：解码与模糊全部交给 [app.dsh.mobile.ui.ImagePipeline] 的后台线程，
     * 主线程只负责把回来的位图包成 Drawable。拖动滑块时连发多档，只有最后一档
     * 会被真正计算和交付（同 tag 覆盖 + vsync 合帧），所以拖动是跟手的。
     */
    private fun refreshPreview() {
        val alpha = CustomVisualPref.settingsBgAlpha(this)
        val blur = CustomVisualPref.settingsBgBlur(this)
        valAlpha.text = getString(R.string.setting_bg_alpha_value, alpha)
        valBlur.text = getString(R.string.setting_bg_blur_value, blur)
        seekAlpha.progress = alpha
        seekBlur.progress = blur

        val file = CustomVisualPref.settingsBgFile(this)
        if (file == null) {
            ImagePipeline.cancel(PREVIEW_TAG)
            imgPreview.setImageDrawable(null)
            imgPreview.visibility = View.GONE
            txtHint.setText(R.string.setting_image_none)
            return
        }
        // 预览用更小的输入尺寸：拖滑块时每档都要重算，1080 宽会掉帧
        ImagePipeline.requestBlur(PREVIEW_TAG, this, file, blur, ImageFx.PREVIEW_EDGE) { bmp ->
            if (isFinishing || isDestroyed) return@requestBlur
            imgPreview.setImageDrawable(
                BitmapDrawable(resources, bmp).apply {
                    // 不透明度按**当前**偏好取：回调到达时偏好可能已经又被改过
                    this.alpha =
                        ImageFx.alphaOf(CustomVisualPref.settingsBgAlpha(this@SettingsBgActivity))
                },
            )
            imgPreview.visibility = View.VISIBLE
            txtHint.setText(R.string.setting_image_set)
        }
    }

    private fun pickImage() {
        val intent = Intent(Intent.ACTION_GET_CONTENT).apply {
            type = "image/*"
            addCategory(Intent.CATEGORY_OPENABLE)
        }
        runCatching { startActivityForResult(intent, REQ_PICK) }.onFailure {
            Toast.makeText(this, R.string.setting_image_none, Toast.LENGTH_SHORT).show()
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQ_PICK) return
        val uri = data?.data ?: return
        if (!CustomVisualPref.importSettingsBg(this, uri)) {
            Toast.makeText(this, R.string.setting_image_none, Toast.LENGTH_SHORT).show()
            return
        }
        applySettingsBackground()
        refreshPreview()
    }

    /** 只关心进度的 SeekBar 回调（start/stop 用不上）。 */
    private abstract class SimpleSeek : SeekBar.OnSeekBarChangeListener {
        abstract fun changed(value: Int)

        /** 松手：需要「一次到位」的昂贵操作挂在这里，而不是每档都做。 */
        open fun stopped() = Unit

        override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
            if (fromUser) changed(progress)
        }

        override fun onStartTrackingTouch(sb: SeekBar?) = Unit
        override fun onStopTrackingTouch(sb: SeekBar?) = stopped()
    }

    private companion object {
        const val REQ_PICK = 4312

        /** 预览图的解码 tag：同一控件只保留最新一档请求 */
        const val PREVIEW_TAG = "settings-bg-preview"
    }
}
