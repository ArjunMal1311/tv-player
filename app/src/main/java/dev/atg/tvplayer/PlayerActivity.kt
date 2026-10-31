package dev.atg.tvplayer

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.view.KeyEvent
import android.view.SurfaceView
import android.view.WindowManager
import android.widget.TextView
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory

// Plays whatever URL it is sent, fullscreen, with no UI. Send with:
//   am start -n dev.atg.tvplayer/.PlayerActivity -a android.intent.action.VIEW -d '<url>' \
//     --es headers 'Referer: https://x\nCookie: a=b'
class PlayerActivity : Activity() {
    private var player: ExoPlayer? = null
    private lateinit var surface: SurfaceView
    private lateinit var status: TextView
    private lateinit var controls: Controls
    private val ticker = android.os.Handler(android.os.Looper.getMainLooper())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        surface = SurfaceView(this)
        status = TextView(this).apply { setTextColor(0xFFFFFFFF.toInt()); textSize = 28f; setPadding(48, 48, 48, 48) }
        val root = android.widget.FrameLayout(this)
        root.addView(surface)
        root.addView(status)
        controls = Controls(this)
        root.addView(controls)
        setContentView(root)
        play(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        play(intent)
    }

    private fun play(intent: Intent) {
        val url = intent.dataString
        if (url == null) { status.text = "Send a link: tv <url>"; return }
        val headers = (intent.getStringExtra("headers") ?: "")
            .split("|~|").mapNotNull { line ->
                val i = line.indexOf(':')
                if (i > 0) line.substring(0, i).trim() to line.substring(i + 1).trim() else null
            }.toMap()
        player?.release()
        val http = DefaultHttpDataSource.Factory()
            .setAllowCrossProtocolRedirects(true)
            .setDefaultRequestProperties(headers)
        val p = ExoPlayer.Builder(this).setMediaSourceFactory(DefaultMediaSourceFactory(http)).build()
        p.setVideoSurfaceView(surface)
        p.addListener(object : Player.Listener {
            override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                status.text = "Can't play: ${error.errorCodeName}"
            }
            override fun onPlaybackStateChanged(state: Int) {
                status.text = if (state == Player.STATE_BUFFERING) "Loading…" else ""
                if (state == Player.STATE_ENDED) finish()
            }
        })
        p.setMediaItem(MediaItem.fromUri(url))
        p.prepare()
        p.playWhenReady = true
        player = p
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        val p = player ?: return super.onKeyDown(keyCode, event)
        when (keyCode) {
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE ->
                p.playWhenReady = !p.playWhenReady
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN -> {}
            KeyEvent.KEYCODE_MEDIA_PLAY -> p.playWhenReady = true
            KeyEvent.KEYCODE_MEDIA_PAUSE -> p.playWhenReady = false
            KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> p.seekTo(p.currentPosition + 10_000)
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_MEDIA_REWIND -> p.seekTo((p.currentPosition - 10_000).coerceAtLeast(0))
            else -> return super.onKeyDown(keyCode, event)
        }
        controls.show()
        tick()
        return true
    }

    private fun tick() {
        val p = player ?: return
        controls.update(!p.playWhenReady, p.currentPosition, if (p.duration > 0) p.duration else 0)
        ticker.removeCallbacksAndMessages(null)
        if (controls.showing) ticker.postDelayed(::tick, 500)
    }

    override fun onDestroy() {
        ticker.removeCallbacksAndMessages(null)
        player?.release()
        super.onDestroy()
    }
}
