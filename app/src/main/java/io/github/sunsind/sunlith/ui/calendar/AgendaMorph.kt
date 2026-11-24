package io.github.sunsind.sunlith.ui.calendar

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.IntState
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.ResolvedTextDirection
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.sunsind.sunlith.data.CalendarEvent
import io.github.sunsind.sunlith.data.key
import java.time.LocalDate

internal val LocalAgendaMorph = compositionLocalOf<AgendaMorph?> { null }

private data class AgendaEventKey(val date: LocalDate, val occurrence: String)

internal class AgendaLabel {
    var position by mutableStateOf<Offset?>(null)
    var layout by mutableStateOf<TextLayoutResult?>(null)
}

internal data class AgendaNowLine(val fraction: Float, val color: Color)

internal class AgendaEndpoint(val event: CalendarEvent, val colors: EventColors, val radius: Dp) {
    var bounds by mutableStateOf(Rect.Zero)
    val banner = event.isTimelineBanner()
    var nowLine by mutableStateOf<AgendaNowLine?>(null)
    val labels = mutableStateMapOf<String, AgendaLabel>()
}

internal class AgendaMorph(private val progress: () -> Float) {
    var timelineBandHeight by mutableStateOf<IntState?>(null)
    val drawingOverlay: Boolean get() = progress().let { it > 0f && it < 1f }

    private val timeline = mutableStateMapOf<AgendaEventKey, AgendaEndpoint>()
    private val list = mutableStateMapOf<AgendaEventKey, AgendaEndpoint>()
    private var viewport by mutableStateOf(Rect.Zero)

    private fun matched(key: AgendaEventKey): Boolean {
        val from = timeline[key]?.bounds ?: return false
        val to = list[key]?.bounds ?: return false
        fun inPage(bounds: Rect) = !bounds.isEmpty && bounds.left < viewport.right && bounds.right > viewport.left
        // The outer clip handles vertical visibility. A card crossing its edge must keep the same
        // morph instead of switching between the timeline/list layers while the picker grows.
        return inPage(from) && inPage(to)
    }

    @Composable
    fun endpoint(event: CalendarEvent, day: LocalDate, inList: Boolean, radius: Dp): AgendaEndpoint {
        val key = AgendaEventKey(day, event.key())
        val endpoints = if (inList) list else timeline
        val colors = eventColors(event)
        val endpoint = remember(key, event, colors, radius) { AgendaEndpoint(event, colors, radius) }
        DisposableEffect(endpoints, key, endpoint) {
            endpoints[key] = endpoint
            onDispose { if (endpoints[key] === endpoint) endpoints.remove(key) }
        }
        return endpoint
    }

    fun card(modifier: Modifier, endpoint: AgendaEndpoint, day: LocalDate): Modifier =
        modifier.onGloballyPositioned { coordinates ->
            endpoint.bounds = Rect(coordinates.positionInRoot(), Size(coordinates.size.width.toFloat(), coordinates.size.height.toFloat()))
        }.drawWithContent {
            val fraction = progress()
            if (fraction <= 0f || fraction >= 1f || !matched(AgendaEventKey(day, endpoint.event.key()))) drawContent()
        }

    fun container(modifier: Modifier, seamColor: Color): Modifier =
        modifier.onGloballyPositioned { coordinates ->
            viewport = Rect(coordinates.positionInRoot(), Size(coordinates.size.width.toFloat(), coordinates.size.height.toFloat()))
        }.drawWithContent {
            drawContent()
            val fraction = progress()
            if (fraction <= 0f || fraction >= 1f) return@drawWithContent
            fun mix(from: Float, to: Float) = from + (to - from) * fraction
            val band = timelineBandHeight?.intValue?.toFloat() ?: 0f
            val targetBand = list.values.asSequence().filter {
                it.banner && !it.bounds.isEmpty && it.bounds.left < viewport.right && it.bounds.right > viewport.left
            }.maxOfOrNull { it.bounds.bottom - viewport.top + 4.dp.toPx() } ?: 0f
            val bandEdge = mix(band, targetBand.coerceAtLeast(0f))

            fun drawCard(key: AgendaEventKey, target: AgendaEndpoint) {
                val source = timeline[key] ?: return
                if (!matched(key)) return
                val from = source.bounds
                val to = target.bounds
                val origin = viewport.topLeft
                val uncutTop = mix(from.top, to.top) - origin.y
                val top = if (target.banner) uncutTop else maxOf(uncutTop, bandEdge)
                val bottom = mix(from.bottom, to.bottom) - origin.y
                val width = mix(from.width, to.width)
                val height = bottom - top
                if (height <= 0f) return
                val topLeft = Offset(mix(from.left, to.left) - origin.x, top)
                val textOrigin = Offset(topLeft.x, uncutTop)
                val radius = minOf(mix(source.radius.toPx(), target.radius.toPx()), width / 2, height / 2)
                // Start with the radius visible at the seam and round the cut edge as the picker opens.
                val topRadius = CornerRadius(maxOf(radius * fraction, radius - (top - uncutTop)))
                val corner = CornerRadius(radius)
                val (background, textColor) = target.colors
                val size = Size(width, height)
                val shape = Path().apply { addRoundRect(RoundRect(Rect(topLeft, size),
                    topLeft = topRadius, topRight = topRadius, bottomLeft = corner, bottomRight = corner)) }
                drawPath(shape, background)
                clipPath(shape) {
                    source.nowLine?.let { marker ->
                        val color = marker.color.copy(alpha = marker.color.alpha * (1f - fraction))
                        drawNowBar(color, textOrigin.y + marker.fraction * mix(from.height, to.height),
                            left = topLeft.x, width = width)
                    }
                    if (target.event.canceled) drawCanceledBars(textColor, topLeft, size)
                    val sourceTitle = source.labels["title"]
                    val targetTitle = target.labels["title"]
                    val start = sourceTitle?.position
                    val end = targetTitle?.position
                    val a = sourceTitle?.layout
                    val b = targetTitle?.layout
                    val sameLines = a != null && b != null &&
                        a.layoutInput.style.hasSameFont(b.layoutInput.style) && a.layoutInput.text == b.layoutInput.text &&
                        a.lineCount == b.lineCount && a.hasVisualOverflow == b.hasVisualOverflow &&
                        (0 until a.lineCount).all { line ->
                            a.getLineStart(line) == b.getLineStart(line) && a.getLineEnd(line) == b.getLineEnd(line)
                        }
                    val baseline = if (start != null && end != null && a != null && b != null) Offset(
                        mix(start.x + a.getLineLeft(0), end.x + b.getLineLeft(0)),
                        mix(start.y + a.firstBaseline, end.y + b.firstBaseline),
                    ) else null

                    fun paint(endpoint: AgendaEndpoint, titlePosition: Offset?, titleLayout: TextLayoutResult?, opacity: Float, drawTitle: Boolean) {
                        val translation = if (baseline != null && titlePosition != null && titleLayout != null) {
                            baseline - titlePosition - Offset(titleLayout.getLineLeft(0), titleLayout.firstBaseline) - origin
                        } else textOrigin - endpoint.bounds.topLeft
                        for ((part, label) in endpoint.labels) {
                            if (part == "title" && !drawTitle) continue
                            val layout = label.layout ?: continue
                            val position = label.position ?: continue
                            if (opacity <= 0f) continue
                            val color = textColor.copy(alpha = textColor.alpha * opacity)
                            if (part == "title") drawEventTitle(layout, position + translation, color)
                            else drawText(layout, color = color, topLeft = position + translation)
                        }
                    }

                    // A shared line uses one paragraph. Drawing both at once makes tracking look uneven.
                    if (sameLines && baseline != null) {
                        val at = baseline - origin - Offset(b.getLineLeft(0), b.firstBaseline)
                        val shadow = lerp(a.layoutInput.style.shadow ?: Shadow.None, b.layoutInput.style.shadow ?: Shadow.None, fraction)
                        drawEventTitle(b, at, textColor, shadow)
                    }
                    // When the paragraph wraps differently, let the old letters get out of the way before showing the new ones.
                    val leaving = ((0.52f - fraction) / 0.24f).coerceIn(0f, 1f)
                    val arriving = ((fraction - 0.48f) / 0.24f).coerceIn(0f, 1f)
                    paint(source, start, a, leaving, !sameLines)
                    paint(target, end, b, arriving, !sameLines)
                }
            }
            for ((key, target) in list) if (!target.banner) drawCard(key, target)
            drawTimelineSeam(bandEdge, seamColor, 1f - fraction)
            if (bandEdge > 0f) clipRect(bottom = bandEdge) {
                for ((key, target) in list) if (target.banner) drawCard(key, target)
            }
        }
}

private fun TextStyle.hasSameFont(other: TextStyle): Boolean =
    fontFamily == other.fontFamily && fontSize == other.fontSize && fontWeight == other.fontWeight &&
        fontStyle == other.fontStyle && fontSynthesis == other.fontSynthesis &&
        letterSpacing == other.letterSpacing && lineHeight == other.lineHeight &&
        textGeometricTransform == other.textGeometricTransform

internal fun DrawScope.drawEventTitle(
    layout: TextLayoutResult, at: Offset, color: Color, shadow: Shadow? = layout.layoutInput.style.shadow,
) {
    if (!layout.hasVisualOverflow || layout.lineCount == 0) {
        drawText(layout, color = color, shadow = shadow, topLeft = at)
        return
    }
    val line = layout.lineCount - 1
    val lineTop = layout.getLineTop(line).coerceAtLeast(0f)
    val width = layout.size.width.toFloat()
    val height = layout.size.height.toFloat()
    val rtl = layout.getParagraphDirection(layout.getLineStart(line)) == ResolvedTextDirection.Rtl
    val shift = if (rtl && layout.didOverflowWidth) (layout.multiParagraph.width - width).coerceAtLeast(0f) else 0f
    val origin = Offset(at.x - shift, at.y)
    val naturalEdge = if (rtl) layout.getLineLeft(line) else layout.getLineRight(line)
    val boundary = if (rtl) shift else width
    val partialGlyph = if (layout.didOverflowWidth) {
        val caret = layout.getOffsetForPosition(Offset(boundary, (lineTop + layout.getLineBottom(line)) / 2))
        (maxOf(layout.getLineStart(line), caret - 1)..minOf(layout.getLineEnd(line) - 1, caret)).firstNotNullOfOrNull { index ->
            layout.getBoundingBox(index).takeIf { it.left < boundary && it.right > boundary }
        }
    } else null
    val fullGlyphEdge = partialGlyph?.let { if (rtl) it.right else it.left } ?: naturalEdge
    val edge = (fullGlyphEdge - shift).coerceIn(0f, width)
    val truncated = layout.didOverflowWidth || layout.multiParagraph.didExceedMaxLines
    val fade = if (truncated) minOf(2.dp.toPx(), if (rtl) width - edge else edge) else 0f
    clipRect(at.x, at.y, at.x + width, at.y + height) {
        if (fade <= 0f) {
            drawText(layout, color = color, shadow = shadow, topLeft = origin)
        } else {
            if (line > 0) clipRect(bottom = at.y + lineTop) {
                drawText(layout, color = color, shadow = shadow, topLeft = origin)
            }
            clipRect(top = at.y + lineTop) {
                val end = edge + shift
                val brush = if (rtl) Brush.horizontalGradient(listOf(Color.Transparent, color), end, end + fade)
                    else Brush.horizontalGradient(listOf(color, Color.Transparent), end - fade, end)
                drawText(layout, brush = brush, shadow = shadow, topLeft = origin)
            }
        }
    }
}

@Composable
internal fun AgendaCardText(
    text: String, color: Color, style: TextStyle, endpoint: AgendaEndpoint?, part: String,
    modifier: Modifier = Modifier, textAlign: TextAlign? = null, fontWeight: FontWeight? = null,
    maxLines: Int = Int.MAX_VALUE, softWrap: Boolean = maxLines != 1,
) {
    val label = if (endpoint == null) null else remember(endpoint, part) { AgendaLabel() }
    if (endpoint != null && label != null) DisposableEffect(endpoint, part, label) {
        endpoint.labels[part] = label
        onDispose { if (endpoint.labels[part] === label) endpoint.labels.remove(part) }
    }
    val fade = part == "title" && maxLines != Int.MAX_VALUE
    var layout by remember { mutableStateOf<TextLayoutResult?>(null) }
    val painted = if (fade) modifier.drawWithContent {
        val title = layout
        if (title?.hasVisualOverflow == true) drawEventTitle(title, Offset.Zero, color) else drawContent()
    } else modifier
    Text(text, if (label == null) painted else painted.onGloballyPositioned { label.position = it.positionInRoot() },
        color = color, style = style, textAlign = textAlign, fontWeight = fontWeight, maxLines = maxLines, softWrap = softWrap,
        overflow = if (fade) TextOverflow.Visible else TextOverflow.Clip,
        onTextLayout = if (!fade && label == null) null else ({ result: TextLayoutResult ->
            if (fade) layout = result
            if (label != null) label.layout = result
        }))
}
