package me.rerere.awara.data.playback

import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import java.io.IOException
import java.io.InterruptedIOException
import java.util.concurrent.ConcurrentHashMap

/** 网络上游可据此排除主动限速产生的低吞吐，不将保活读取当成节点退化。 */
internal interface PlaybackReadModeAware {
    var isKeepAliveRead: Boolean
}

/**
 * 将网络读取与 Media3 消费分开：无人等待时以 10 KiB/s 预读，有消费者等待时立即全速。
 *
 * 环形队列固定为 1 MiB，满后停止读取；不丢字节、不写额外磁盘、不绕过播放器的内存保护。 每次 open 使用独立上游和线程，seek 或关闭后迟到的数据不能进入下一次请求。
 */
@OptIn(UnstableApi::class)
internal class PlaybackReadAheadDataSource private constructor(private val owner: Factory) :
    DataSource {
    private val listeners = mutableListOf<TransferListener>()
    @Volatile private var session: Session? = null

    /** 每个播放器持有一个工厂，退出时统一取消所有正在打开或预读的网络请求。 */
    class Factory(
        private val upstream: DataSource.Factory,
        private val capacityBytes: Int = 1024 * 1024,
        private val keepAliveBytesPerSecond: Int = 10 * 1024,
    ) : DataSource.Factory, AutoCloseable {
        private val lifecycleLock = Any()
        private val active = ConcurrentHashMap.newKeySet<PlaybackReadAheadDataSource>()
        private var closed = false

        init {
            require(capacityBytes > 0 && keepAliveBytesPerSecond > 0)
        }

        override fun createDataSource(): DataSource = PlaybackReadAheadDataSource(this)

        /** 登记先于网络打开，使尚未取得响应头的请求同样能被退出操作取消。 */
        internal fun start(source: PlaybackReadAheadDataSource, dataSpec: DataSpec): Long {
            val current =
                synchronized(lifecycleLock) {
                    if (closed) throw IOException("播放预读工厂已关闭")
                    check(source.session == null) { "请先关闭上一次预读请求" }
                    Session(
                            upstream.createDataSource(),
                            dataSpec,
                            source.listeners.toList(),
                            capacityBytes,
                            keepAliveBytesPerSecond,
                        )
                        .also {
                            source.session = it
                            active.add(source)
                            it.start()
                        }
                }
            return current.awaitOpen()
        }

        /** 状态摘除与重开共用生命周期锁，旧请求关闭不能移除新请求的登记。 */
        internal fun stop(source: PlaybackReadAheadDataSource) {
            val closing =
                synchronized(lifecycleLock) {
                    source.session?.also {
                        source.session = null
                        active.remove(source)
                    }
                }
            closing?.close()
        }

        override fun close() {
            val closing =
                synchronized(lifecycleLock) {
                    if (closed) return
                    closed = true
                    active.toList()
                }
            var failure: IOException? = null
            closing.forEach { source ->
                try {
                    source.close()
                } catch (error: IOException) {
                    if (failure == null) failure = error else failure?.addSuppressed(error)
                }
            }
            failure?.let { throw it }
        }
    }

    override fun addTransferListener(transferListener: TransferListener) {
        check(session == null) { "监听器必须在打开预读请求前添加" }
        if (!listeners.contains(transferListener)) listeners.add(transferListener)
    }

    override fun open(dataSpec: DataSpec): Long = owner.start(this, dataSpec)

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        require(offset >= 0 && length >= 0 && offset <= buffer.size - length)
        if (length == 0) return 0
        return (session ?: throw IOException("播放预读请求尚未打开")).read(buffer, offset, length)
    }

    override fun getUri(): Uri? = session?.uri

    override fun getResponseHeaders(): Map<String, List<String>> = session?.headers ?: emptyMap()

    override fun close() {
        owner.stop(this)
    }

    /** 一个请求独占的生产者、环形队列及结束状态；所有队列状态由同一监视器保护。 */
    private class Session(
        private val upstream: DataSource,
        private val dataSpec: DataSpec,
        private val listeners: List<TransferListener>,
        capacityBytes: Int,
        private val keepAliveBytesPerSecond: Int,
    ) : AutoCloseable {
        private val lock = Object()
        private val closeLock = Any()
        private val queue = ByteArray(capacityBytes)
        private var head = 0
        private var queuedBytes = 0
        private var waitingReader = false
        private var opened = false
        private var closed = false
        private var finished = false
        private var failure: IOException? = null
        private var resolvedLength = C.LENGTH_UNSET.toLong()
        private var remaining = C.LENGTH_UNSET.toLong()
        private var nextKeepAliveReadNs = 0L
        @Volatile
        var uri: Uri? = null
            private set

        @Volatile
        var headers: Map<String, List<String>> = emptyMap()
            private set

        /** 网络操作只由此线程顺序执行，close 可从播放器线程打断阻塞的上游读取。 */
        private val worker =
            Thread({ produce() }, "awara-playback-read-ahead").apply { isDaemon = true }

        fun start() = worker.start()

        fun awaitOpen(): Long =
            synchronized(lock) {
                while (!opened && !finished && !closed) waitForChange()
                if (closed) throw IOException("播放预读请求已取消")
                if (!opened) failure?.let { throw it }
                resolvedLength
            }

        /** 先交付已成功读取的前缀，再暴露其后的错误，保持上层断点位置与实际交付一致。 */
        fun read(buffer: ByteArray, offset: Int, length: Int): Int =
            synchronized(lock) {
                waitingReader = true
                lock.notifyAll()
                try {
                    while (queuedBytes == 0 && !finished && !closed) waitForChange()
                    if (closed) throw IOException("播放预读请求已取消")
                    if (queuedBytes == 0) {
                        failure?.let { throw it }
                        return C.RESULT_END_OF_INPUT
                    }
                    val count = minOf(length, queuedBytes, queue.size - head)
                    queue.copyInto(buffer, offset, head, head + count)
                    head = (head + count) % queue.size
                    queuedBytes -= count
                    lock.notifyAll()
                    count
                } finally {
                    waitingReader = false
                }
            }

        /** 等待可被新需求、关闭或中断唤醒，绝不在持有队列锁时进行网络 I/O。 */
        private fun waitForChange(nanos: Long = 0L) {
            try {
                if (nanos == 0L) lock.wait()
                else lock.wait(nanos / 1_000_000L, (nanos % 1_000_000L).toInt())
            } catch (error: InterruptedException) {
                Thread.currentThread().interrupt()
                throw InterruptedIOException("播放预读等待已中断").apply { initCause(error) }
            }
        }

        /** 空闲小块节流，有实际消费需求立即跳过节流等待；队列满时完全停止读取。 */
        private fun produce() {
            val scratch = ByteArray(minOf(queue.size, 32 * 1024))
            try {
                synchronized(lock) { if (closed) return }
                listeners.forEach(upstream::addTransferListener)
                val length = upstream.open(dataSpec)
                snapshotResponse()
                synchronized(lock) {
                    resolvedLength = length
                    remaining =
                        if (dataSpec.length != C.LENGTH_UNSET.toLong()) dataSpec.length else length
                    opened = true
                    lock.notifyAll()
                }
                while (true) {
                    val slowRead: Boolean
                    val requested: Int
                    synchronized(lock) {
                        while (true) {
                            if (closed || remaining == 0L) return
                            val delayNs = nextKeepAliveReadNs - System.nanoTime()
                            if (queuedBytes == queue.size) waitForChange()
                            else if (!waitingReader && delayNs > 0L) waitForChange(delayNs)
                            else break
                        }
                        slowRead = !waitingReader
                        requested =
                            minOf(
                                queue.size - queuedBytes,
                                if (slowRead) minOf(1024, scratch.size) else scratch.size,
                                if (remaining >= 0L)
                                    minOf(remaining, Int.MAX_VALUE.toLong()).toInt()
                                else Int.MAX_VALUE,
                            )
                    }
                    (upstream as? PlaybackReadModeAware)?.isKeepAliveRead = slowRead
                    val count = upstream.read(scratch, 0, requested)
                    snapshotResponse()
                    if (count == C.RESULT_END_OF_INPUT) {
                        if (remaining > 0L) throw IOException("播放预读响应提前结束")
                        return
                    }
                    if (count <= 0 || count > requested) throw IOException("播放预读返回无效字节数")
                    synchronized(lock) {
                        if (closed) return
                        val tail = (head + queuedBytes) % queue.size
                        val first = minOf(count, queue.size - tail)
                        scratch.copyInto(queue, tail, 0, first)
                        if (first < count) scratch.copyInto(queue, 0, first, count)
                        queuedBytes += count
                        if (remaining >= 0L) remaining -= count
                        nextKeepAliveReadNs =
                            System.nanoTime() +
                                if (slowRead)
                                    count.toLong() * 1_000_000_000L / keepAliveBytesPerSecond
                                else 100_000_000L
                        lock.notifyAll()
                    }
                }
            } catch (error: Exception) {
                synchronized(lock) {
                    if (!closed) failure = error as? IOException ?: IOException(error)
                }
            } finally {
                snapshotResponse()
                try {
                    synchronized(closeLock) { upstream.close() }
                } catch (error: Exception) {
                    synchronized(lock) {
                        if (!closed && failure == null)
                            failure = error as? IOException ?: IOException(error)
                    }
                }
                synchronized(lock) {
                    finished = true
                    lock.notifyAll()
                }
            }
        }

        /** 保存响应信息，使队列中仍有数据时即使上游已结束也符合 DataSource 查询契约。 */
        private fun snapshotResponse() {
            uri = upstream.uri
            headers = upstream.responseHeaders
        }

        override fun close() {
            synchronized(lock) {
                if (closed) return
                closed = true
                lock.notifyAll()
            }
            worker.interrupt()
            synchronized(closeLock) { upstream.close() }
        }
    }
}
