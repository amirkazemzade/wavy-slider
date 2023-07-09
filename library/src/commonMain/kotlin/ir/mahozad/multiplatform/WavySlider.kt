package ir.mahozad.multiplatform

import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.*
import androidx.compose.foundation.interaction.DragInteraction
import androidx.compose.foundation.interaction.Interaction
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.MutatorMutex
import androidx.compose.foundation.MutatePriority
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.*
import androidx.compose.material.ripple.rememberRipple
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.debugInspectorInfo
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlin.math.*

// TODO: Add wave velocity property (speed + direction)
// TODO: Add ability to stop the wave animation manually with a boolean property
// TODO: Stop the wave animation when the wave height is zero
// TODO: Add Material 3 variant

/**
 * The direction of wave movement.
 *
 * By default, and also when set to [WaveAnimationDirection.UNSPECIFIED],
 * it moves from right to left on LTR layouts and from left to right on RTL layouts.
 */
enum class WaveAnimationDirection {
    /**
     * Always move from right to left regardless of the layout direction.
     */
    RTL,
    /**
     * Always move from left to right regardless of the layout direction.
     */
    LTR,
    /**
     * Based on layout direction; on LTR move from right to left and on RTL move from left to right.
     */
    UNSPECIFIED
}

private val ThumbRadius = 10.dp
private val ThumbRippleRadius = 24.dp
private val ThumbDefaultElevation = 1.dp
private val ThumbPressedElevation = 6.dp
private val TrackHeight = 4.dp
private val SliderHeight = 48.dp
private val SliderMinWidth = 144.dp // TODO: clarify min width
private val DefaultSliderConstraints = Modifier.widthIn(min = SliderMinWidth).heightIn(max = SliderHeight)

internal expect val defaultTrackThickness: Dp
internal expect val defaultWaveSize: Dp

@Composable
internal expect fun focusModifier(requester: (FocusRequester?) -> Unit): Modifier

@Composable
internal expect fun keyEventModifier(
    enabled: Boolean,
    value: Float,
    isRtl: Boolean,
    onValueChangeState: State<(Float) -> Unit>
): Modifier

/**
 * A wavy slider much like the <a href="https://material.io/components/sliders" class="external" target="_blank">Material Design slider</a>.
 *
 * By setting [waveHeight] to `0.dp` it becomes just a regular Material slider.
 *
 * This component can also be used as a progress bar.
 *
 * Note that range sliders do not make sense for the wavy slider.
 * So, there is no RangeWavySlider counterpart.
 *
 * @param value current value of the WavySlider
 * @param onValueChange lambda in which value should be updated
 * @param modifier modifiers for the WavySlider layout
 * @param enabled whether or not component is enabled and can be interacted with or not
 * @param onValueChangeFinished lambda to be invoked when value change has ended. This callback
 * shouldn't be used to update the wavy slider value (use [onValueChange] for that), but rather to
 * know when the user has completed selecting a new value by ending a drag or a click.
 * @param interactionSource the [MutableInteractionSource] representing the stream of
 * [Interaction]s for this WavySlider. You can create and pass in your own remembered
 * [MutableInteractionSource] if you want to observe [Interaction]s and customize the
 * appearance / behavior of this WavySlider in different [Interaction]s.
 * @param colors [SliderColors] that will be used to determine the color of the WavySlider parts in
 * different state. See [SliderDefaults.colors] to customize.
 * @param waveLength the distance over which the wave's shape repeats
 * @param waveHeight the total height of the wave (from crest to trough) (in other words, amplitude * 2)
 * @param waveThickness the thickness of the active line (whether animated or not)
 * @param trackThickness the thickness of the inactive line
 * @param animationDirection the direction of wave movement which is, by default,
 * from right to left for LTR layouts and from left to right for RTL layouts.
 * Setting to [WaveAnimationDirection.UNSPECIFIED] also does the same thing
 * @param shouldFlatten whether to decrease the wave height the farther it is from the thumb
 */
@Composable
fun WavySlider(
    value: Float,
    onValueChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    onValueChangeFinished: (() -> Unit)? = null,
    interactionSource: MutableInteractionSource = remember { MutableInteractionSource() },
    colors: SliderColors = SliderDefaults.colors(),
    waveLength: Dp = defaultWaveSize,
    waveHeight: Dp = defaultWaveSize,
    waveThickness: Dp = defaultTrackThickness,
    trackThickness: Dp? = defaultTrackThickness,
    animationDirection: WaveAnimationDirection = WaveAnimationDirection.UNSPECIFIED,
    shouldFlatten: Boolean = false
) {
    // TODO: Add valueRange (and steps if it makes sense) to the parameters for feature-parity with Slider

    val isRtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    val onValueChangeState = rememberUpdatedState(onValueChange)
    var focusRequester: FocusRequester? = remember { null }
    BoxWithConstraints(
        modifier
            .minimumInteractiveComponentSize()
            .requiredSizeIn(minWidth = ThumbRadius * 2, minHeight = ThumbRadius * 2)
            .sliderSemantics(
                value,
                enabled,
                onValueChange,
                onValueChangeFinished
            )
            .then(focusModifier { focusRequester = it })
            .focusable(enabled, interactionSource)
            .then(keyEventModifier(enabled, value, isRtl, onValueChangeState))
    ) {
        val widthPx = constraints.maxWidth.toFloat()
        val maxPx: Float
        val minPx: Float

        with(LocalDensity.current) {
            maxPx = max(widthPx - ThumbRadius.toPx(), 0f)
            minPx = min(ThumbRadius.toPx(), maxPx)
        }

        fun scaleToUserValue(offset: Float) =
            scale(minPx, maxPx, offset, 0f, 1f)

        fun scaleToOffset(userValue: Float) =
            scale(0f, 1f, userValue, minPx, maxPx)

        val rawOffset = remember { mutableStateOf(scaleToOffset(value)) }
        val pressOffset = remember { mutableStateOf(0f) }

        val draggableState = remember(minPx, maxPx, 0f..1f) {
            SliderDraggableState {
                rawOffset.value = (rawOffset.value + it + pressOffset.value)
                pressOffset.value = 0f
                val offsetInTrack = rawOffset.value.coerceIn(minPx, maxPx)
                onValueChangeState.value.invoke(scaleToUserValue(offsetInTrack))
            }
        }

        CorrectValueSideEffect(::scaleToOffset, 0f..1f, minPx..maxPx, rawOffset, value)

        val gestureEndAction = rememberUpdatedState { _: Float ->
            focusRequester?.requestFocus()
            if (!draggableState.isDragging) {
                // check ifDragging in case the change is still in progress (touch -> drag case)
                onValueChangeFinished?.invoke()
            }
        }
        val press = Modifier.sliderTapModifier(
            draggableState,
            interactionSource,
            widthPx,
            isRtl,
            rawOffset,
            gestureEndAction,
            pressOffset,
            enabled
        )

        val drag = Modifier.draggable(
            orientation = Orientation.Horizontal,
            reverseDirection = isRtl,
            enabled = enabled,
            interactionSource = interactionSource,
            onDragStopped = { velocity -> gestureEndAction.value.invoke(velocity) },
            startDragImmediately = draggableState.isDragging,
            state = draggableState
        )

        val coerced = value.coerceIn(0f, 1f)
        val fraction = calcFraction(0f, 1f, coerced)
        SliderImpl(
            enabled,
            fraction,
            colors,
            maxPx - minPx,
            interactionSource,
            modifier = press.then(drag),
//
//
//
//
            trackThickness,
            shouldFlatten,
            waveLength,
            waveHeight,
            waveThickness,
            animationDirection
        )
    }
}

@Composable
private fun SliderImpl(
    enabled: Boolean,
    positionFraction: Float,
    colors: SliderColors,
    width: Float,
    interactionSource: MutableInteractionSource,
    modifier: Modifier,
//
//
//
//
    trackThickness: Dp?,
    shouldFlatten: Boolean,
    waveWidth: Dp,
    waveHeight: Dp,
    waveThickness: Dp,
    animationDirection: WaveAnimationDirection
) {
    Box(modifier.then(DefaultSliderConstraints)) {
        val trackStrokeWidth: Float
        val thumbPx: Float
        val widthDp: Dp
        with(LocalDensity.current) {
            trackStrokeWidth = TrackHeight.toPx()
            thumbPx = ThumbRadius.toPx()
            widthDp = width.toDp()
        }

        val thumbSize = ThumbRadius * 2
        val offset = widthDp * positionFraction

        Track(
            Modifier.fillMaxSize(),
            colors,
            enabled,
            positionFraction,
            thumbPx,
            trackStrokeWidth,
//
//
//
//
            trackThickness,
            shouldFlatten,
            waveWidth,
            waveHeight,
            waveThickness,
            animationDirection
        )
        SliderThumb(Modifier, offset, interactionSource, colors, enabled, thumbSize)
    }
}

@Composable
private fun Track(
    modifier: Modifier,
    colors: SliderColors,
    enabled: Boolean,
    positionFractionEnd: Float,
    thumbPx: Float,
    trackStrokeWidth: Float,
//
//
//
//
    trackThickness: Dp?,
    shouldFlatten: Boolean,
    waveWidth: Dp,
    waveHeight: Dp,
    waveThickness: Dp,
    animationDirection: WaveAnimationDirection
) {
    val inactiveTrackColor = colors.trackColor(enabled, active = false)
    val activeTrackColor = colors.trackColor(enabled, active = true)
    val waveHeightAnimated by animateFloatAsState(waveHeight.value, tween(1000, easing = LinearEasing))
    val wavePosition by rememberInfiniteTransition()
        .animateFloat(
            initialValue = 0f,
            targetValue = if (animationDirection == WaveAnimationDirection.LTR) {
                waveWidth.value
            } else if (animationDirection == WaveAnimationDirection.RTL) {
                -waveWidth.value
            } else if (LocalLayoutDirection.current == LayoutDirection.Rtl) {
                waveWidth.value
            } else {
                -waveWidth.value
            },
            animationSpec = infiniteRepeatable(
                animation = tween(durationMillis = 2000, easing = LinearEasing),
                repeatMode = RepeatMode.Restart
            )
        )
    Canvas(Modifier.fillMaxWidth().height(48.dp)) {
        val isRtl = layoutDirection == LayoutDirection.Rtl
        val sliderLeft = Offset(thumbPx, center.y)
        val sliderRight = Offset(size.width - thumbPx, center.y)
        val sliderStart = if (isRtl) sliderRight else sliderLeft
        val sliderEnd = if (isRtl) sliderLeft else sliderRight
        val sliderValueOffset = Offset(
            sliderStart.x + (sliderEnd.x - sliderStart.x) * positionFractionEnd,
            center.y
        )
        if (trackThickness != null && trackThickness > 0.dp) {
            drawLine(
                strokeWidth = trackThickness.value,
                color = inactiveTrackColor.value,
                start = sliderValueOffset,
                end = sliderEnd,
                cap = StrokeCap.Round
            )
        }
        val wave = Path().apply {
            val startX = sliderStart.x + /* One extra required wave at the start */ waveWidth.value * if (isRtl) 1 else -1
            val length = (sliderValueOffset.x - startX).absoluteValue + /* One extra required wave at the end */ waveWidth.value
            val totalWaveCount = ceil(length / waveWidth.value).toInt()
            val heightFactors = if (shouldFlatten) {
                generateHeightFactors(totalWaveCount)
            } else {
                FloatArray(totalWaveCount)
            }
            moveTo(startX, center.y)
            for (i in 0 until totalWaveCount) {
                relativeCubicTo(
                    /* Control 1: */ waveWidth.value / 2 * if (isRtl) -1 else 1, (waveHeightAnimated / 2) * if (shouldFlatten) heightFactors[i] else 1f,
                    /* Control 2: */ waveWidth.value / 2 * if (isRtl) -1 else 1, (-waveHeightAnimated / 2) * if (shouldFlatten) heightFactors[i] else 1f,
                    /* End point: */ waveWidth.value * if (isRtl) -1 else 1, 0f
                )
            }
        }
        // Could also have used .clipToBounds() on Canvas modifier
        clipRect(
            left = if (isRtl) sliderValueOffset.x else sliderLeft.x - (/* To match the size of material slider as it has round cap */ waveThickness.value / 2),
            right = if (isRtl) sliderRight.x + (/* To match the size of material slider as it has round cap */ waveThickness.value / 2) else sliderValueOffset.x
        ) {
            translate(left = wavePosition) {
                drawPath(
                    path = wave,
                    color = activeTrackColor.value,
                    style = Stroke(waveThickness.value)
                )
            }
        }
    }
}

@Composable
private fun BoxScope.SliderThumb(
    modifier: Modifier,
    offset: Dp,
    interactionSource: MutableInteractionSource,
    colors: SliderColors,
    enabled: Boolean,
    thumbSize: Dp
) {
    Box(
        Modifier
            .padding(start = offset)
            .align(Alignment.CenterStart)) {
        val interactions = remember { mutableStateListOf<Interaction>() }
        LaunchedEffect(interactionSource) {
            interactionSource.interactions.collect { interaction ->
                when (interaction) {
                    is PressInteraction.Press -> interactions.add(interaction)
                    is PressInteraction.Release -> interactions.remove(interaction.press)
                    is PressInteraction.Cancel -> interactions.remove(interaction.press)
                    is DragInteraction.Start -> interactions.add(interaction)
                    is DragInteraction.Stop -> interactions.remove(interaction.start)
                    is DragInteraction.Cancel -> interactions.remove(interaction.start)
                }
            }
        }

        val elevation = if (interactions.isNotEmpty()) {
            ThumbPressedElevation
        } else {
            ThumbDefaultElevation
        }
        Spacer(
            modifier
                .size(thumbSize, thumbSize)
                .indication(
                    interactionSource = interactionSource,
                    indication = rememberRipple(bounded = false, radius = ThumbRippleRadius)
                )
                .hoverable(interactionSource = interactionSource)
                .shadow(if (enabled) elevation else 0.dp, CircleShape, clip = false)
                .background(colors.thumbColor(enabled).value, CircleShape)
        )
    }
}

// Scale x1 from a1..b1 range to a2..b2 range
private fun scale(a1: Float, b1: Float, x1: Float, a2: Float, b2: Float) =
    lerp(a2, b2, calcFraction(a1, b1, x1))

// Calculate the 0..1 fraction that `pos` value represents between `a` and `b`
private fun calcFraction(a: Float, b: Float, pos: Float) =
    (if (b - a == 0f) 0f else (pos - a) / (b - a)).coerceIn(0f, 1f)

private fun lerp(start: Float, stop: Float, fraction: Float) =
    (start * (1 - fraction) + stop * fraction)

@Composable
private fun CorrectValueSideEffect(
    scaleToOffset: (Float) -> Float,
    valueRange: ClosedFloatingPointRange<Float>,
    trackRange: ClosedFloatingPointRange<Float>,
    valueState: MutableState<Float>,
    value: Float
) {
    SideEffect {
        val error = (valueRange.endInclusive - valueRange.start) / 1000
        val newOffset = scaleToOffset(value)
        if (abs(newOffset - valueState.value) > error) {
            if (valueState.value in trackRange) {
                valueState.value = newOffset
            }
        }
    }
}

private fun Modifier.sliderSemantics(
    value: Float,
    enabled: Boolean,
    onValueChange: (Float) -> Unit,
    onValueChangeFinished: (() -> Unit)? = null
): Modifier {
    val coerced = value.coerceIn(0f, 1f)
    return semantics {
        if (!enabled) disabled()
        setProgress(
            action = { targetValue ->
                val newValue = targetValue.coerceIn(0f, 1f)
                // This is to keep it consistent with AbsSeekbar.java: return false if no
                // change from current.
                if (newValue == coerced) {
                    false
                } else {
                    onValueChange(newValue)
                    onValueChangeFinished?.invoke()
                    true
                }
            }
        )
    }.progressSemantics(value, 0f..1f, 0)
}

private fun Modifier.sliderTapModifier(
    draggableState: DraggableState,
    interactionSource: MutableInteractionSource,
    maxPx: Float,
    isRtl: Boolean,
    rawOffset: State<Float>,
    gestureEndAction: State<(Float) -> Unit>,
    pressOffset: MutableState<Float>,
    enabled: Boolean
) = composed(
    factory = {
        if (enabled) {
            val scope = rememberCoroutineScope()
            pointerInput(draggableState, interactionSource, maxPx, isRtl) {
                detectTapGestures(
                    onPress = { pos ->
                        val to = if (isRtl) maxPx - pos.x else pos.x
                        pressOffset.value = to - rawOffset.value
                        try {
                            awaitRelease()
                        } catch (_: GestureCancellationException) {
                            pressOffset.value = 0f
                        }
                    },
                    onTap = {
                        scope.launch {
                            draggableState.drag(MutatePriority.UserInput) {
                                // just trigger animation, press offset will be applied
                                dragBy(0f)
                            }
                            gestureEndAction.value.invoke(0f)
                        }
                    }
                )
            }
        } else {
            this
        }
    },
    inspectorInfo = debugInspectorInfo {
        name = "sliderTapModifier"
        properties["draggableState"] = draggableState
        properties["interactionSource"] = interactionSource
        properties["maxPx"] = maxPx
        properties["isRtl"] = isRtl
        properties["rawOffset"] = rawOffset
        properties["gestureEndAction"] = gestureEndAction
        properties["pressOffset"] = pressOffset
        properties["enabled"] = enabled
    })

private class SliderDraggableState(
    val onDelta: (Float) -> Unit
) : DraggableState {

    var isDragging by mutableStateOf(false)
        private set

    private val dragScope: DragScope = object : DragScope {
        override fun dragBy(pixels: Float): Unit = onDelta(pixels)
    }

    private val scrollMutex = MutatorMutex()

    override suspend fun drag(
        dragPriority: MutatePriority,
        block: suspend DragScope.() -> Unit
    ): Unit = coroutineScope {
        isDragging = true
        scrollMutex.mutateWith(dragScope, dragPriority, block)
        isDragging = false
    }

    override fun dispatchRawDelta(delta: Float) {
        return onDelta(delta)
    }
}
