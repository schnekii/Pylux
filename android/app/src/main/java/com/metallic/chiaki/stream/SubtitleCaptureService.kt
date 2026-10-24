// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL
package com.metallic.chiaki.stream

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.SystemClock
import android.util.Log

/**
 * Captures the TV's displayed Remote Play image after explicit user consent,
 * OCRs the lower subtitle region, and broadcasts translated text to StreamActivity.
 */
internal class SubtitleCaptureService : Service() {
    companion object {
        const val ACTION_START = "com.pylux.stream.subtitle.START"
        const val ACTION_STOP = "com.pylux.stream.subtitle.STOP"
        const val ACTION_TEXT = "com.pylux.stream.subtitle.TEXT"
        const val EXTRA_RESULT_CODE = "projection_result_code"
        const val EXTRA_RESULT_DATA = "projection_result_data"
        const val EXTRA_TEXT = "translated_text"
        private const val CHANNEL_ID = "subtitle_translation"
        private const val NOTIFICATION_ID = 8217
        private const val TAG = "SubtitleCapture"
    }

    private var projection: MediaProjection? = null
    private var display: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private var engine: SubtitleTranslationEngine? = null
    private var workerThread: HandlerThread? = null
    private var worker: Handler? = null
    private var lastFrameAt = 0L

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_START -> {
                if (projection != null) return START_STICKY
                createNotificationChannel()
                val notification = buildNotification()
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    startForeground(
                        NOTIFICATION_ID,
                        notification,
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
                    )
                } else {
                    startForeground(NOTIFICATION_ID, notification)
                }
                val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, 0)
                val resultData = if (Build.VERSION.SDK_INT >= 33)
                    intent.getParcelableExtra(EXTRA_RESULT_DATA, Intent::class.java)
                else
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra(EXTRA_RESULT_DATA) as? Intent
                if (resultData == null) {
                    Log.w(TAG, "Missing screen-capture consent result")
                    stopSelf()
                    return START_NOT_STICKY
                }
                try {
                    val manager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
                    projection = manager.getMediaProjection(resultCode, resultData)
                    startCapture()
                } catch (error: Exception) {
                    Log.e(TAG, "Could not start screen capture", error)
                    stopSelf()
                }
            }
        }
        return START_NOT_STICKY
    }

    private fun startCapture() {
        val metrics = resources.displayMetrics
        val width = metrics.widthPixels
        val height = metrics.heightPixels
        val density = metrics.densityDpi
        if (width <= 0 || height <= 0) throw IllegalStateException("Invalid display size")

        workerThread = HandlerThread("SubtitleOCR").also { it.start() }
        worker = Handler(workerThread!!.looper)
        engine = SubtitleTranslationEngine(
            this,
            onTranslation = { text ->
                sendBroadcast(Intent(ACTION_TEXT).setPackage(packageName).putExtra(EXTRA_TEXT, text))
            },
            onError = { message ->
                sendBroadcast(Intent(ACTION_TEXT).setPackage(packageName).putExtra(EXTRA_TEXT, message))
            }
        )

        imageReader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)
        imageReader!!.setOnImageAvailableListener({ reader ->
            val now = SystemClock.elapsedRealtime()
            if (now - lastFrameAt < 900L) {
                reader.acquireLatestImage()?.close()
                return@setOnImageAvailableListener
            }
            val image = reader.acquireLatestImage() ?: return@setOnImageAvailableListener
            lastFrameAt = now
            try {
                val bitmap = imageToBitmap(image)
                val top = (bitmap.height * 0.60f).toInt().coerceAtMost(bitmap.height - 1)
                val subtitleRegion = Bitmap.createBitmap(
                    bitmap, 0, top, bitmap.width, bitmap.height - top
                )
                bitmap.recycle()
                engine?.processSubtitleBitmap(subtitleRegion)
            } catch (error: Exception) {
                Log.w(TAG, "Could not process captured frame", error)
            } finally {
                image.close()
            }
        }, worker)

        projection?.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() {
                Log.i(TAG, "Screen capture stopped by Android")
                stopSelf()
            }
        }, worker)

        display = projection?.createVirtualDisplay(
            "PyluxSubtitleCapture",
            width,
            height,
            density,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            imageReader!!.surface,
            null,
            worker
        )
    }

    private fun imageToBitmap(image: Image): Bitmap {
        val plane = image.planes[0]
        val buffer = plane.buffer
        val pixelStride = plane.pixelStride
        val rowStride = plane.rowStride
        val rowPadding = rowStride - pixelStride * image.width
        val paddedWidth = image.width + rowPadding / pixelStride
        val padded = Bitmap.createBitmap(paddedWidth, image.height, Bitmap.Config.ARGB_8888)
        buffer.rewind()
        padded.copyPixelsFromBuffer(buffer)
        val cropped = Bitmap.createBitmap(padded, 0, 0, image.width, image.height)
        padded.recycle()
        return cropped
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID, "Turkish subtitle translation", NotificationManager.IMPORTANCE_LOW
            ).apply { description = "Runs on-device OCR while translating game subtitles." }
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun buildNotification(): Notification {
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            android.app.Notification.Builder(this, CHANNEL_ID)
        else
            @Suppress("DEPRECATION")
            android.app.Notification.Builder(this)
        return builder
            .setSmallIcon(com.pylux.stream.R.drawable.ic_launcher_foreground)
            .setContentTitle("Pylux Türkçe Altyazı")
            .setContentText("Ekrandaki altyazılar Türkçeye çevriliyor")
            .setOngoing(true)
            .build()
    }

    override fun onDestroy() {
        display?.release()
        display = null
        imageReader?.setOnImageAvailableListener(null, null)
        imageReader?.close()
        imageReader = null
        projection?.stop()
        projection = null
        engine?.close()
        engine = null
        workerThread?.quitSafely()
        workerThread = null
        worker = null
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }
}
