package com.droidacp.ui

import android.widget.Toast
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Text
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import com.droidacp.core.acp.ContentBlock
import com.droidacp.core.chat.PictureFile
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

private const val MAX_ZOOM = 6f
private const val DOUBLE_TAP_ZOOM = 2.5f

/**
 * A picture from the chat, full screen: pinch or double-tap to zoom, drag to look around
 * while zoomed. Back or ✕ closes it. With its [source], *Save* puts it in Pictures/DroidACP
 * (Android 10+) and *Share* offers it to other apps, as the agent sent it.
 */
@Composable
fun ImageViewer(bitmap: ImageBitmap, description: String, source: ContentBlock.Image? = null, onDismiss: () -> Unit) {
    // The activity's context, from outside the dialog, to start the share sheet.
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    fun toast(text: String) = Toast.makeText(context, text, Toast.LENGTH_LONG).show()

    /** Runs [work] off the main thread, then what it returns on it (a toast, the share sheet). */
    fun act(what: String, work: (PictureFile) -> (() -> Unit)) {
        val picture = source?.let { PictureFile.of(it.data, it.mimeType) }
        if (picture == null) {
            toast("Couldn't $what the picture: it isn't readable")
            return
        }
        busy = true
        scope.launch {
            try {
                withContext(Dispatchers.IO) { work(picture) }()
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                toast("Couldn't $what the picture: ${e.message ?: e.javaClass.simpleName}")
            } finally {
                busy = false
            }
        }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        var scale by remember { mutableFloatStateOf(1f) }
        var offset by remember { mutableStateOf(Offset.Zero) }
        var size by remember { mutableStateOf(IntSize.Zero) }

        // Keep the picture over the screen: at most half its growth either way.
        fun clamp(o: Offset, s: Float): Offset {
            val maxX = size.width * (s - 1f) / 2f
            val maxY = size.height * (s - 1f) / 2f
            return Offset(o.x.coerceIn(-maxX, maxX), o.y.coerceIn(-maxY, maxY))
        }

        Box(
            Modifier
                .fillMaxSize()
                .background(Color.Black)
                .onSizeChanged { size = it }
                .pointerInput(Unit) {
                    detectTapGestures(
                        onDoubleTap = { tap ->
                            if (scale > 1f) {
                                scale = 1f
                                offset = Offset.Zero
                            } else {
                                // Zoom in on the point tapped: it stays under the finger.
                                val center = Offset(size.width / 2f, size.height / 2f)
                                scale = DOUBLE_TAP_ZOOM
                                offset = clamp((center - tap) * (DOUBLE_TAP_ZOOM - 1f), DOUBLE_TAP_ZOOM)
                            }
                        },
                    )
                }
                .pointerInput(Unit) {
                    detectTransformGestures { centroid, pan, zoom, _ ->
                        val newScale = (scale * zoom).coerceIn(1f, MAX_ZOOM)
                        // The point between the fingers stays under them as the scale changes.
                        val center = Offset(size.width / 2f, size.height / 2f)
                        val ratio = newScale / scale
                        val moved = (centroid - center) * (1f - ratio) + offset * ratio + pan
                        scale = newScale
                        offset = if (newScale == 1f) Offset.Zero else clamp(moved, newScale)
                    }
                },
        ) {
            Image(
                bitmap = bitmap,
                contentDescription = description,
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        scaleX = scale
                        scaleY = scale
                        translationX = offset.x
                        translationY = offset.y
                    },
            )
            // Along the top, by ✕: the bottom of a dialog's window can sit under the navigation bar.
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .safeDrawingPadding()
                    .padding(12.dp),
            ) {
                if (source != null) {
                    if (PictureActions.canSave) {
                        FilledTonalButton(enabled = !busy, onClick = {
                            act("save") { val where = PictureActions.save(context, it); { toast("Saved to $where") } }
                        }) { Text("Save") }
                    }
                    FilledTonalButton(enabled = !busy, onClick = {
                        act("share") { val send = PictureActions.shareIntent(context, it); { context.startActivity(send) } }
                    }) {
                        Icon(Icons.Default.Share, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("Share")
                    }
                }
                FilledTonalIconButton(onClick = onDismiss) { Icon(Icons.Default.Close, contentDescription = "Close") }
            }
        }
    }
}
