package me.rerere.awara.ui.component.player

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput

/**
 * 播放器手势滑动快进/快退 Modifier。
 *
 * 规格要求：
 * 1. 超过系统 touchSlop 且横向明显占优（absX >= 1.5 * absY）才判定为 seek；
 * 2. 竖向占优或未达到 1.5 倍阈值拒绝整个序列，后续不能转横；
 * 3. 系统顶/左右/底边缘避免 seek（默认至少 24dp 保护）；
 * 4. 首次 down 子 Slider/按钮若已消费（requireUnconsumed = true）则父不接管；
 * 5. 拖动过程中消费 move 事件，使外层 detectTapGestures 自动取消 tap；
 * 6. 取消/多指/抢占时不提交 seek，清理预览；松手时提交一次；
 * 7. 支持通过 onSuppressTap 门控防止竖向滑动误触单击。
 *
 * @param previewState 共享预览状态
 * @param getDuration 获取当前视频总时长
 * @param getCurrentPosition 获取当前真实播放位置（单一来源）
 * @param onSeekCommit 松手提交目标时间
 * @param onGestureActiveChanged 手势活跃状态与控制栏显隐联动
 * @param onSuppressTap 触发非横向手势时的防误触门控
 * @param canStartAt 根据窗口坐标及系统安全区判断能否开始
 */
fun Modifier.playerSeekGestures(
    previewState: SeekPreviewState,
    gestureKey: Any,
    canSeek: () -> Boolean,
    canStartAt: (Offset) -> Boolean,
    getDuration: () -> Long,
    getCurrentPosition: () -> Long,
    onSeekCommit: (Long) -> Unit,
    onGestureActiveChanged: (Boolean) -> Unit,
    onSuppressTap: () -> Unit = {},
): Modifier =
    pointerInput(previewState, gestureKey) {
        val touchSlop = viewConfiguration.touchSlop

        awaitEachGesture {
            // 读取未被子组件消费的 down 事件
            val down = awaitFirstDown(requireUnconsumed = true, pass = PointerEventPass.Main)
            val startX = down.position.x
            val startY = down.position.y
            val downPointerId = down.id

            val duration = getDuration()
            val width = size.width.toFloat()

            // 边缘安全保护及时长检查：在边缘区域或无有效时长时不响应 seek
            if (duration <= 0L || width <= 0f || !canSeek() || !canStartAt(down.position)) {
                return@awaitEachGesture
            }

            var decision = GestureDirectionDecision.Undecided
            var isDragging = false
            val startPosition = getCurrentPosition()

            try {
                while (true) {
                    val event = awaitPointerEvent(PointerEventPass.Main)
                    val currentPointer = event.changes.firstOrNull { it.id == downPointerId }

                    // 多指介入、被抢占或 pointer 丢失
                    if (
                        currentPointer == null ||
                            currentPointer.isConsumed ||
                            event.changes.size > 1 ||
                            !canSeek()
                    ) {
                        onSuppressTap()
                        if (isDragging) {
                            previewState.cancel()
                            onGestureActiveChanged(false)
                            isDragging = false
                        }
                        break
                    }

                    // 松手抬起
                    if (!currentPointer.pressed) {
                        if (isDragging) {
                            val commitTarget = previewState.commit()
                            if (commitTarget != null) {
                                onSeekCommit(commitTarget)
                            }
                            onGestureActiveChanged(false)
                            isDragging = false
                        }
                        break
                    }

                    val dx = currentPointer.position.x - startX
                    val dy = currentPointer.position.y - startY

                    if (decision == GestureDirectionDecision.Undecided) {
                        decision = evaluateGestureDirection(dx, dy, touchSlop)
                        if (decision == GestureDirectionDecision.LockedHorizontal) {
                            if (previewState.startGesture(startPosition, width, duration)) {
                                isDragging = true
                                onSuppressTap()
                                onGestureActiveChanged(true)
                                currentPointer.consume()
                                previewState.updateGesture(dx.toDouble())
                            } else {
                                decision = GestureDirectionDecision.Rejected
                            }
                        } else if (decision == GestureDirectionDecision.Rejected) {
                            // 竖向占优或横向不占优，门控抑制 tap 并退出当前序列
                            onSuppressTap()
                            break
                        }
                    } else if (decision == GestureDirectionDecision.LockedHorizontal) {
                        currentPointer.consume()
                        val totalDx = (currentPointer.position.x - startX).toDouble()
                        previewState.updateGesture(totalDx)
                        onGestureActiveChanged(true)
                    }
                    if (!isDragging) {
                        val finalEvent = awaitPointerEvent(PointerEventPass.Final)
                        if (finalEvent.changes.any { it.isConsumed }) {
                            onSuppressTap()
                            break
                        }
                    }
                }
            } finally {
                if (isDragging) {
                    previewState.cancel()
                    onGestureActiveChanged(false)
                    isDragging = false
                }
            }
        }
    }
