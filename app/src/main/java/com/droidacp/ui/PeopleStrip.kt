package com.droidacp.ui

import android.graphics.Bitmap
import android.widget.Toast
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
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
import com.droidacp.core.people.Look
import com.droidacp.core.people.PixelPeople
import com.droidacp.core.people.Sprite
import com.droidacp.core.people.StripPerson
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** The break room's picture in sprites.txt: a pose with no person in it. */
private const val ROOM_POSE = "room"

/**
 * The Pixel People above the message box: one small person per PlasmaLLM tab, posed by what
 * its agent is doing, on a bar in its agent's colours. A tap opens that tab's chat (which
 * never stops anything: the chat left goes on in its tab); a long press shows its name for a
 * moment.
 *
 * With PlasmaLLM's break room ([breakRoom], protocol 19), the long press also offers "Take a
 * break", and those on a break sit in the room at the strip's end instead: tapping the room
 * lists them, and picking one brings it back. A break only moves the person; its agent goes on
 * working. [onBreak] asks PlasmaLLM, and the strip changes when PlasmaLLM's new state comes.
 */
@Composable
fun PeopleStrip(
    people: List<StripPerson>,
    breakRoom: Boolean,
    onOpen: (StripPerson) -> Unit,
    onBreak: suspend (tabId: String, onBreak: Boolean) -> String?,
) {
    if (people.isEmpty()) return
    val size = pixelPerfect(76.dp)
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    fun setBreak(person: StripPerson, on: Boolean) {
        val tabId = person.tabId ?: return
        scope.launch {
            onBreak(tabId, on)?.let { Toast.makeText(context, it, Toast.LENGTH_LONG).show() }
        }
    }

    val (away, here) = people.partition { breakRoom && it.onBreak }
    Row(
        Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 12.dp, vertical = 2.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
    ) {
        here.forEach { person ->
            PersonView(person, size, onOpen, onTakeBreak = if (breakRoom && person.tabId != null) ({ setBreak(person, true) }) else null)
        }
        if (breakRoom) RoomView(away, size, people.first().look) { setBreak(it, false) }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun PersonView(person: StripPerson, size: Dp, onOpen: (StripPerson) -> Unit, onTakeBreak: (() -> Unit)?) {
    val bitmap = remember(person.pose, person.look) { spriteBitmap(person.pose, person.look) }
    val bar = remember(person.look.agent) { PixelPeople.default.bar(person.look.agent).toImageBitmap() }
    val pixel = size / PixelPeople.SIZE
    var showName by remember { mutableStateOf(false) }
    var menu by remember { mutableStateOf(false) }
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
                    onLongClickLabel = if (onTakeBreak != null) "Show the name, or send on a break" else "Show the name",
                    onClick = { if (!person.front) onOpen(person) },
                    onLongClick = { if (onTakeBreak != null) menu = true else showName = true },
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
        if (onTakeBreak != null) {
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                Column(Modifier.widthIn(max = 300.dp).padding(horizontal = 16.dp, vertical = 6.dp)) {
                    Text(person.name + (person.agent?.let { " · $it" } ?: ""), style = MaterialTheme.typography.labelLarge, maxLines = 1)
                    Text(
                        doing(person),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                HorizontalDivider()
                DropdownMenuItem(
                    text = { Text("Take a break") },
                    onClick = {
                        menu = false
                        onTakeBreak()
                    },
                )
            }
        }
    }
}

/**
 * The break room at the strip's end: a couch with how many are on it. A tap lists them, and
 * picking one brings it back ([onBack]). Lit like the chat's own person when this chat's is in
 * there.
 */
@Composable
private fun RoomView(away: List<StripPerson>, size: Dp, anyLook: Look, onBack: (StripPerson) -> Unit) {
    val bitmap = remember(anyLook) { spriteBitmap(ROOM_POSE, anyLook) }
    var open by remember { mutableStateOf(false) }
    val count = away.size
    Box {
        Column(
            Modifier
                .background(
                    if (away.any { it.front }) MaterialTheme.colorScheme.primary.copy(alpha = 0.16f) else Color.Transparent,
                    RoundedCornerShape(14.dp),
                )
                .clickable(onClickLabel = "Show who's on a break") { open = true }
                .semantics {
                    contentDescription = when (count) {
                        0 -> "Break room, empty"
                        1 -> "Break room: ${away.first().name}"
                        else -> "Break room: $count on a break"
                    }
                },
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Box {
                Image(bitmap, contentDescription = null, filterQuality = FilterQuality.None, modifier = Modifier.size(size))
                if (count > 0) {
                    Surface(
                        shape = CircleShape,
                        color = MaterialTheme.colorScheme.primary,
                        contentColor = MaterialTheme.colorScheme.onPrimary,
                        modifier = Modifier.align(Alignment.TopEnd).padding(2.dp),
                    ) {
                        Text("$count", style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(horizontal = 6.dp, vertical = 1.dp))
                    }
                }
            }
            Text("Break room", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            Text(
                if (count == 0) "No one's on a break. Press and hold someone to send them here." else "On a break: tap one to bring them back",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.widthIn(max = 300.dp).padding(horizontal = 16.dp, vertical = 6.dp),
            )
            for (person in away) {
                val small = remember(person.pose, person.look) { spriteBitmap(person.pose, person.look) }
                DropdownMenuItem(
                    // The person as on the strip, beside its name (an icon slot would shrink it).
                    text = {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Image(small, contentDescription = null, filterQuality = FilterQuality.None, modifier = Modifier.size(pixelPerfect(48.dp)))
                            Column(Modifier.widthIn(max = 240.dp)) {
                                Text(
                                    person.name + (person.agent?.let { " · $it" } ?: "") + if (person.front) " · this chat" else "",
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Text(
                                    doing(person),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                    },
                    trailingIcon = if (person.busy) ({ CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp) }) else null,
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                    onClick = {
                        open = false
                        onBack(person)
                    },
                )
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
                doing(person),
                style = MaterialTheme.typography.labelSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** "Reading files: README.md", for a label. */
private fun doing(person: StripPerson): String =
    Activity.describe(person.activity).replaceFirstChar { it.uppercase() } + (person.detail?.let { ": $it" } ?: "")

/** About [target], rounded so each sprite pixel is a whole number of screen pixels (crisp edges). */
@Composable
private fun pixelPerfect(target: Dp): Dp {
    val density = LocalDensity.current
    val perPixel = (with(density) { target.toPx() } / PixelPeople.SIZE).toInt().coerceAtLeast(1)
    return with(density) { (perPixel * PixelPeople.SIZE).toDp() }
}

private fun spriteBitmap(pose: String, look: Look): ImageBitmap =
    PixelPeople.default.render(pose, look).toImageBitmap()

private fun Sprite.toImageBitmap(): ImageBitmap =
    Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888).asImageBitmap()
