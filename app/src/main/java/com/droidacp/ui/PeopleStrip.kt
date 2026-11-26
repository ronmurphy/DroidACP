package com.droidacp.ui

import android.graphics.Bitmap
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import com.droidacp.core.people.Activity
import com.droidacp.core.people.PixelPeople
import com.droidacp.core.people.Sprite
import com.droidacp.core.people.StripPerson
import kotlinx.coroutines.delay

/**
 * The Pixel People above the message box: one small person per PlasmaLLM tab, posed by what
 * its agent is doing, on a bar in its agent's colours. A tap opens that tab's chat (which
 * never stops anything: the chat left goes on in its tab); a long press shows its name for a
 * moment.
 */
@Composable
fun PeopleStrip(people: List<StripPerson>, onOpen: (StripPerson) -> Unit) {
    if (people.isEmpty()) return
    val size = pixelPerfect(76.dp)
    Row(
        Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 12.dp, vertical = 2.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
    ) {
        people.forEach { person -> PersonView(person, size, onOpen) }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun PersonView(person: StripPerson, size: Dp, onOpen: (StripPerson) -> Unit) {
    val bitmap = remember(person.pose, person.look) { spriteBitmap(person.pose, person) }
    val bar = remember(person.look.agent) { PixelPeople.default.bar(person.look.agent).toImageBitmap() }
    val pixel = size / PixelPeople.SIZE
    var showName by remember { mutableStateOf(false) }
    LaunchedEffect(showName) {
        if (showName) {
            delay(2500)
            showName = false
        }
    }
    Box(contentAlignment = Alignment.Center) {
        Column(
            Modifier
                // Not clipped: a bubble or Zzz may reach the corner.
                .background(
                    if (person.front) MaterialTheme.colorScheme.primary.copy(alpha = 0.16f) else Color.Transparent,
                    RoundedCornerShape(14.dp),
                )
                .combinedClickable(
                    onClickLabel = if (person.front) null else "Open ${person.name}'s chat",
                    onLongClickLabel = "Show the name",
                    onClick = { if (!person.front) onOpen(person) },
                    onLongClick = { showName = true },
                )
                .semantics { contentDescription = person.description + if (person.front) ", this chat" else "" },
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Image(bitmap, contentDescription = null, filterQuality = FilterQuality.None, modifier = Modifier.size(size))
            // The agent's colours, whatever the person wears.
            Image(
                bar,
                contentDescription = null,
                filterQuality = FilterQuality.None,
                modifier = Modifier
                    .padding(bottom = pixel * 2)
                    .size(pixel * PixelPeople.BAR_WIDTH, pixel * PixelPeople.BAR_HEIGHT),
            )
        }
        if (showName) {
            Popup(
                alignment = Alignment.TopCenter,
                offset = IntOffset(0, with(LocalDensity.current) { -58.dp.roundToPx() }),
                onDismissRequest = { showName = false },
                properties = PopupProperties(focusable = false),
            ) {
                NameLabel(person)
            }
        }
    }
}

@Composable
private fun NameLabel(person: StripPerson) {
    Surface(
        shape = RoundedCornerShape(10.dp),
        color = MaterialTheme.colorScheme.inverseSurface,
        contentColor = MaterialTheme.colorScheme.inverseOnSurface,
        shadowElevation = 4.dp,
    ) {
        Column(
            Modifier.widthIn(max = 300.dp).padding(horizontal = 12.dp, vertical = 6.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                person.name + (person.agent?.let { " · $it" } ?: ""),
                style = MaterialTheme.typography.labelLarge,
                maxLines = 1,
            )
            Text(
                Activity.describe(person.activity).replaceFirstChar { it.uppercase() } + (person.detail?.let { ": $it" } ?: ""),
                style = MaterialTheme.typography.labelSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** About [target], rounded so each sprite pixel is a whole number of screen pixels (crisp edges). */
@Composable
private fun pixelPerfect(target: Dp): Dp {
    val density = LocalDensity.current
    val perPixel = (with(density) { target.toPx() } / PixelPeople.SIZE).toInt().coerceAtLeast(1)
    return with(density) { (perPixel * PixelPeople.SIZE).toDp() }
}

private fun spriteBitmap(pose: String, person: StripPerson): ImageBitmap =
    PixelPeople.default.render(pose, person.look).toImageBitmap()

private fun Sprite.toImageBitmap(): ImageBitmap =
    Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888).asImageBitmap()
