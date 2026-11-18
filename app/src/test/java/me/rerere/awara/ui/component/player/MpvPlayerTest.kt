package me.rerere.awara.ui.component.player

import android.graphics.SurfaceTexture
import android.net.Uri
import android.os.Looper
import android.view.Surface
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.HttpDataSource
import java.io.IOException
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@UnstableApi
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class MpvPlayerTest {

    private var playerToRelease: MpvPlayer? = null
    private var textureToRelease: SurfaceTexture? = null
    private var surfaceToRelease: Surface? = null

    @After
    fun tearDown() {
        playerToRelease?.release()
        surfaceToRelease?.release()
        textureToRelease?.release()
    }

    private fun idleMain() {
        shadowOf(Looper.getMainLooper()).idle()
    }

    @Test
    fun preparePreservesOriginalMediaItemAndAppliesGpuSoftDecodeOptions() {
        val core = FakeMpvCore()
        var capturedError: PlaybackException? = null
        val player =
            MpvPlayer(
                core = core,
                applicationLooper = Looper.getMainLooper(),
                expandedBufferEnabled = true,
                uriTranslator = { "http://127.0.0.1:45678/media/tok123" },
            ).also {
                playerToRelease = it
                it.addListener(
                    object : Player.Listener {
                        override fun onPlayerError(error: PlaybackException) {
                            capturedError = error
                        }
                    }
                )
            }

        assertEquals("null", core.options["vo"])
        assertEquals("android", core.options["gpu-context"])
        assertEquals("yes", core.options["opengl-es"])
        assertEquals("no", core.options["hwdec"])
        assertEquals("bilinear", core.options["scale"])
        assertEquals("no", core.options["hr-seek"])
        assertEquals("0.2", core.options["cache-pause-wait"])
        assertEquals((128 * 1024 * 1024).toString(), core.options["demuxer-max-bytes"])

        val originalUri = Uri.parse("https://cdn.iwara.tv/file/hi10p.mp4?sig=xyz")
        val item = MediaItem.Builder().setMediaId("vid-1").setUri(originalUri).build()
        player.setMediaItem(item, 42_500L)
        player.playWhenReady = true
        player.prepare()
        idleMain()

        // 对外 MediaItem 必须保持原始 URI，只有底层 loadfile 使用本地代理 URL
        assertEquals(originalUri, player.currentMediaItem?.localConfiguration?.uri)
        assertEquals("42.500", core.properties["start"])
        assertEquals(listOf("loadfile", "http://127.0.0.1:45678/media/tok123", "replace"), core.commands.last())
        assertEquals(Player.STATE_BUFFERING, player.playbackState)
        assertEquals(42_500L, player.currentPosition)

        core.properties["dwidth"] = 3840
        core.properties["dheight"] = 2160
        core.properties["seekable"] = true
        core.emitEvent(MpvCoreEvent.FILE_LOADED)
        core.emitDouble("duration", 120.0)
        core.emitDouble("time-pos", 42.5)
        core.emitDouble("demuxer-cache-time", 57.5)
        idleMain()

        assertEquals("none", core.properties["start"])
        assertEquals(Player.STATE_READY, player.playbackState)
        assertEquals(120_000L, player.duration)
        assertEquals(42_500L, player.currentPosition)
        assertEquals(57_500L, player.bufferedPosition)
        assertEquals(15_000L, player.totalBufferedDuration)
        assertTrue(player.isCurrentMediaItemSeekable)
        assertEquals(VideoSize(3840, 2160), player.videoSize)
        assertTrue(player.currentTracks.isTypeSelected(C.TRACK_TYPE_VIDEO))
        assertNull(capturedError)
    }

    @Test
    fun surfaceAttachTracksFirstFrameAndDetachRestoresVoNull() {
        val core = FakeMpvCore()
        var firstFrames = 0
        val player =
            MpvPlayer(
                core = core,
                uriTranslator = { "http://127.0.0.1:1234/media/abc" },
            ).also {
                playerToRelease = it
                it.addListener(
                    object : Player.Listener {
                        override fun onRenderedFirstFrame() {
                            firstFrames++
                        }
                    }
                )
            }

        val item = MediaItem.fromUri("content://downloads/hi10p.mp4")
        player.setMediaItem(item)
        player.prepare()
        core.properties["dwidth"] = 1920
        core.properties["dheight"] = 1080
        core.emitEvent(MpvCoreEvent.FILE_LOADED)
        core.emitEvent(MpvCoreEvent.PLAYBACK_RESTART)
        idleMain()

        // 未绑定有效 Surface 时不应提前宣告首帧渲染完成
        assertEquals(0, firstFrames)

        val texture = SurfaceTexture(1).also { textureToRelease = it }
        val surface = Surface(texture).also { surfaceToRelease = it }
        assertTrue(surface.isValid)
        player.setVideoSurface(surface)
        idleMain()

        assertEquals(1, core.attachCount)
        assertEquals("gpu", core.properties["vo"])
        assertEquals("yes", core.properties["force-window"])
        assertEquals(1, firstFrames)

        player.clearVideoSurface()
        idleMain()
        assertEquals("null", core.properties["vo"])
        assertEquals("no", core.properties["force-window"])
        assertEquals(1, core.detachCount)
    }

    @Test
    fun unexpectedEndFilePropagatesHttpAuthCauseWhileStopAndReplaceSuppressError() {
        val core = FakeMpvCore()
        val errors = mutableListOf<PlaybackException>()
        var authCauseToReturn: HttpDataSource.InvalidResponseCodeException? = null
        val player =
            MpvPlayer(
                core = core,
                uriTranslator = { "http://127.0.0.1:8888/media/tok" },
                errorCauseResolver = {
                    val current = authCauseToReturn
                    authCauseToReturn = null
                    current
                },
            ).also {
                playerToRelease = it
                it.addListener(
                    object : Player.Listener {
                        override fun onPlayerError(error: PlaybackException) {
                            errors.add(error)
                        }
                    }
                )
            }

        val uri = Uri.parse("https://cdn.example.com/a.mp4")
        player.setMediaItem(MediaItem.fromUri(uri))
        player.prepare()
        // stop() 随后触发的 END_FILE 不应上报错误
        player.stop()
        core.emitEvent(MpvCoreEvent.END_FILE)
        idleMain()
        assertTrue(errors.isEmpty())

        // 真正播放中遭遇上游 403，应当保留 InvalidResponseCodeException 以便上层刷新 URL
        player.setMediaItem(MediaItem.fromUri(uri))
        player.prepare()
        authCauseToReturn =
            HttpDataSource.InvalidResponseCodeException(
                403,
                "Forbidden",
                IOException("403"),
                emptyMap(),
                DataSpec(uri),
                ByteArray(0),
            )
        core.emitEvent(MpvCoreEvent.END_FILE)
        idleMain()

        assertEquals(1, errors.size)
        assertEquals(PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS, errors[0].errorCode)
        assertNotNull(errors[0].cause as? HttpDataSource.InvalidResponseCodeException)
    }

    @Test
    fun seekAfterEofClearsEndedStateAndSyncsSpeedAndLoop() {
        val core = FakeMpvCore()
        val player = MpvPlayer(core = core).also { playerToRelease = it }
        player.setMediaItem(MediaItem.fromUri("https://cdn.example.com/v.mp4"))
        player.repeatMode = Player.REPEAT_MODE_ONE
        player.playbackParameters = PlaybackParameters(1.5f)
        player.playWhenReady = true
        player.prepare()

        assertEquals("inf", core.properties["loop-file"])
        assertEquals(1.5, core.properties["speed"] as Double, 0.001)

        core.emitEvent(MpvCoreEvent.FILE_LOADED)
        core.emitDouble("duration", 60.0)
        // 单曲循环模式关闭后到达结尾才应进入 STATE_ENDED
        player.repeatMode = Player.REPEAT_MODE_OFF
        assertEquals("no", core.properties["loop-file"])
        core.emitBoolean("eof-reached", true)
        idleMain()
        assertEquals(Player.STATE_ENDED, player.playbackState)
        assertFalse(player.isPlaying)

        player.seekTo(15_000L)
        idleMain()
        assertEquals(Player.STATE_BUFFERING, player.playbackState)
        assertEquals(listOf("seek", "15.000", "absolute"), core.commands.last())
        assertEquals(false, core.properties["pause"])
    }

    @Test
    fun switchingToNewSurfaceDetachesPreviousVoBeforeAttachingNewSurface() {
        val core = FakeMpvCore()
        val player = MpvPlayer(core = core).also { playerToRelease = it }

        val textureA = SurfaceTexture(1).also { textureToRelease = it }
        val surfaceA = Surface(textureA).also { surfaceToRelease = it }
        val textureB = SurfaceTexture(2)
        val surfaceB = Surface(textureB)

        try {
            player.setVideoSurface(surfaceA)
            idleMain()
            core.surfaceOperations.clear()

            // 不先显式 clearVideoSurface 直接切换到新 Surface 时，必须先卸载 vo=null 并 detachSurface 再挂新 Surface
            player.setVideoSurface(surfaceB)
            idleMain()

            assertEquals(
                listOf(
                    "prop:vo=null",
                    "prop:force-window=no",
                    "detachSurface",
                    "attachSurface",
                    "prop:force-window=yes",
                    "prop:vo=gpu",
                ),
                core.surfaceOperations,
            )
        } finally {
            surfaceB.release()
            textureB.release()
        }
    }

    @Test
    fun setVideoSurfaceHolderSkipsDuplicateSurfaceSizePropertyUpdates() {
        val core = FakeMpvCore()
        val player = MpvPlayer(core = core).also { playerToRelease = it }

        val texture = SurfaceTexture(3)
        val surface = Surface(texture)
        val holder =
            object : android.view.SurfaceHolder {
                private val callbacks = mutableListOf<android.view.SurfaceHolder.Callback>()
                private var frame = android.graphics.Rect(0, 0, 1280, 720)

                override fun addCallback(callback: android.view.SurfaceHolder.Callback) {
                    callbacks.add(callback)
                }

                override fun removeCallback(callback: android.view.SurfaceHolder.Callback) {
                    callbacks.remove(callback)
                }

                override fun isCreating(): Boolean = false

                @Deprecated("Deprecated in SurfaceHolder")
                override fun setType(type: Int) = Unit

                override fun setFixedSize(width: Int, height: Int) = Unit

                override fun setSizeFromLayout() = Unit

                override fun setFormat(format: Int) = Unit

                override fun setKeepScreenOn(screenOn: Boolean) = Unit

                override fun lockCanvas(): android.graphics.Canvas? = null

                override fun lockCanvas(dirty: android.graphics.Rect?): android.graphics.Canvas? = null

                override fun unlockCanvasAndPost(canvas: android.graphics.Canvas?) = Unit

                override fun getSurfaceFrame(): android.graphics.Rect = frame

                override fun getSurface(): Surface = surface

                fun dispatchChanged(width: Int, height: Int) {
                    frame = android.graphics.Rect(0, 0, width, height)
                    callbacks.toList().forEach {
                        it.surfaceChanged(this, android.graphics.PixelFormat.RGBA_8888, width, height)
                    }
                }
            }

        try {
            player.setVideoSurfaceHolder(holder)
            idleMain()
            assertEquals("1280x720", core.properties["android-surface-size"])
            assertEquals(1, core.surfaceSizeUpdates)

            // 系统紧随 surfaceCreated 后若重复回调相同尺寸，不重复向 libmpv 下发属性
            holder.dispatchChanged(1280, 720)
            idleMain()
            assertEquals(1, core.surfaceSizeUpdates)

            // 切换到全屏尺寸时应当更新并通知新宽高，但不触发 detachSurface/vo=null
            holder.dispatchChanged(1920, 1080)
            idleMain()
            assertEquals("1920x1080", core.properties["android-surface-size"])
            assertEquals(2, core.surfaceSizeUpdates)
            assertEquals(0, core.detachCount)
        } finally {
            surface.release()
            texture.release()
        }
    }

    private class FakeMpvCore : MpvCore {
        val options = mutableMapOf<String, String>()
        val properties = mutableMapOf<String, Any?>()
        val commands = mutableListOf<List<String>>()
        val surfaceOperations = mutableListOf<String>()
        private var coreListener: MpvCoreListener? = null
        var attachCount = 0
        var detachCount = 0
        var surfaceSizeUpdates = 0

        override fun init() = Unit

        override fun destroy() = Unit

        override fun attachSurface(surface: Surface) {
            attachCount++
            surfaceOperations.add("attachSurface")
        }

        override fun detachSurface() {
            detachCount++
            surfaceOperations.add("detachSurface")
        }

        override fun command(cmd: Array<String>) {
            commands.add(cmd.toList())
        }

        override fun setOptionString(name: String, value: String): Int {
            options[name] = value
            return 0
        }

        override fun setPropertyString(name: String, value: String) {
            properties[name] = value
            if (name == "vo" || name == "force-window") {
                surfaceOperations.add("prop:$name=$value")
            } else if (name == "android-surface-size") {
                surfaceSizeUpdates++
            }
        }

        override fun setPropertyBoolean(name: String, value: Boolean) {
            properties[name] = value
        }

        override fun setPropertyDouble(name: String, value: Double) {
            properties[name] = value
        }

        override fun getPropertyInt(name: String): Int? = properties[name] as? Int

        override fun getPropertyBoolean(name: String): Boolean? = properties[name] as? Boolean

        override fun observeProperty(name: String, format: MpvPropertyFormat) = Unit

        override fun setListener(listener: MpvCoreListener?) {
            coreListener = listener
        }

        fun emitEvent(event: MpvCoreEvent) {
            coreListener?.onEvent(event)
        }

        fun emitDouble(name: String, value: Double) {
            properties[name] = value
            coreListener?.onDoubleProperty(name, value)
        }

        fun emitBoolean(name: String, value: Boolean) {
            properties[name] = value
            coreListener?.onBooleanProperty(name, value)
        }
    }
}
