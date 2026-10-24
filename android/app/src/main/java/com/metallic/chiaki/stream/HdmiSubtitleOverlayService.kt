// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL
package com.metallic.chiaki.stream

import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.view.Gravity
import android.view.WindowManager
import android.widget.TextView
import com.pylux.stream.R

/**
 * Experimental always-on-top caption layer.
 *
 * The initial purpose is to test whether this particular Android/Google TV
 * compositor keeps application overlays visible when the TV switches to an
 * external HDMI input. It does not yet receive subtitles from the Remote Play
 * decoder; translated text can later be sent with ACTION_UPDATE_TEXT.
 */
internal class HdmiSubtitleOverlayService : Service() {
    companion object {
        const val ACTION_SHOW = "com.pylux.stream.hdmi_overlay.SHOW"
        const val ACTION_UPDATE_TEXT = "com.pylux.stream.hdmi_overlay.UPDATE_TEXT"
        const val ACTION_STOP = "com.pylux.stream.hdmi_overlay.STOP"
        const val EXTRA_TEXT = "overlay_text"
    }

    private var windowManager: WindowManager? = null
    private var captionView: TextView? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                removeCaption()
                stopSelf()
            }
            ACTION_SHOW -> {
                val text = intent.getStringExtra(EXTRA_TEXT)
                    ?: getString(R.string.hdmi_overlay_test_text)
                showCaption(text)
            }
            ACTION_UPDATE_TEXT -> {
                val text = intent.getStringExtra(EXTRA_TEXT).orEmpty()
                if (text.isBlank()) removeCaption() else showCaption(text)
            }
        }
        return START_STICKY
    }

    private fun showCaption(text: String) {
        if (!Settings.canDrawOverlays(this)) {
            stopSelf()
            return
        }

        val manager = windowManager
            ?: (getSystemService(Context.WINDOW_SERVICE) as WindowManager).also {
                windowManager = it
            }
        val view = captionView ?: createCaptionView().also { captionView = it }
        view.text = text

        if (view.parent == null) {
            val windowType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            } else {
                @Suppress("DEPRECATION")
                WindowManager.LayoutParams.TYPE_PHONE
            }
            val params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                windowType,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
                y = (resources.displayMetrics.heightPixels * 0.08f).toInt()
                width = (resources.displayMetrics.widthPixels * 0.88f).toInt()
            }

            try {
                manager.addView(view, params)
            } catch (_: Exception) {
                captionView = null
            }
        }
    }

    private fun createCaptionView(): TextView = TextView(this).apply {
        text = getString(R.string.hdmi_overlay_test_text)
        textSize = 26f
        setTextColor(Color.WHITE)
        typeface = Typeface.create("sans-serif", Typeface.BOLD)
        gravity = Gravity.CENTER
        setPadding(24, 16, 24, 16)
        background = GradientDrawable().apply {
            setColor(0xCC000000.toInt())
            cornerRadius = 14f
        }
        elevation = 16f
    }

    private fun removeCaption() {
        val view = captionView ?: return
        try {
            if (view.parent != null) {
                windowManager?.removeView(view)
            }
        } catch (_: Exception) {
            // The Android TV window manager may remove overlay windows itself
            // while the input or display configuration is changing.
        }
        captionView = null
    }

    override fun onDestroy() {
        removeCaption()
        super.onDestroy()
    }
}
