package com.srihari.vintix.ui.camera

import android.graphics.Bitmap
import android.graphics.Canvas as AndroidCanvas
import android.graphics.Color as AndroidColor
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.snapping.rememberSnapFlingBehavior
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.srihari.vintix.effects.CameraProfile
import com.srihari.vintix.effects.RetroBitmapProcessor
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

object ThumbnailCache {
    private val cache = ConcurrentHashMap<String, ImageBitmap>()

    suspend fun getOrGenerate(profile: CameraProfile): ImageBitmap = withContext(Dispatchers.Default) {
        val key = profile.displayName
        cache[key] ?: run {
            val bitmap = createProcessedThumbnail(profile)
            val imageBitmap = bitmap.asImageBitmap()
            cache[key] = imageBitmap
            imageBitmap
        }
    }
}

@Composable
fun ProfileSelector(
    profiles: List<CameraProfile>,
    selectedProfile: String,
    onProfileSelected: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val listState = rememberLazyListState()
    val snappingBehavior = rememberSnapFlingBehavior(lazyListState = listState)

    LaunchedEffect(selectedProfile, profiles) {
        val index = profiles.indexOfFirst { it.displayName == selectedProfile }
        if (index >= 0) {
            listState.animateScrollToItem(index)
        }
    }

    BoxWithConstraints(modifier = modifier.fillMaxWidth().height(92.dp)) {
        val cardWidth = 122.dp
        val sidePadding = if (maxWidth > cardWidth) (maxWidth / 2) - (cardWidth / 2) else 20.dp
        LazyRow(
            state = listState,
            flingBehavior = snappingBehavior,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(horizontal = sidePadding),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            items(profiles, key = { it.displayName }) { profile ->
                ProfileCard(
                    profile = profile,
                    selected = profile.displayName == selectedProfile,
                    onClick = { onProfileSelected(profile.displayName) },
                    modifier = Modifier.width(cardWidth),
                )
            }
        }
    }
}

@Composable
private fun ProfileCard(
    profile: CameraProfile,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val borderColor by animateColorAsState(
        targetValue = if (selected) Color(0xFFFF8A1F) else Color.White.copy(alpha = 0.18f),
        animationSpec = tween(180),
        label = "profile-border",
    )
    val backgroundColor by animateColorAsState(
        targetValue = if (selected) Color(0xFF17120D) else Color.Black.copy(alpha = 0.50f),
        animationSpec = tween(180),
        label = "profile-bg",
    )
    val scale by animateFloatAsState(
        targetValue = if (selected) 1f else 0.94f,
        animationSpec = tween(180),
        label = "profile-scale",
    )
    val preview by produceState<ImageBitmap?>(initialValue = null, profile.displayName) {
        value = ThumbnailCache.getOrGenerate(profile)
    }

    Column(
        modifier = modifier
            .scale(scale)
            .clip(RoundedCornerShape(8.dp))
            .background(backgroundColor)
            .border(1.dp, borderColor, RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
            .padding(5.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        val bmp = preview
        if (bmp != null) {
            Image(
                bitmap = bmp,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp)
                    .clip(RoundedCornerShape(5.dp)),
            )
        } else {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp)
                    .clip(RoundedCornerShape(5.dp))
                    .background(Color.DarkGray),
            )
        }
        Text(
            text = profile.displayName,
            color = if (selected) Color(0xFFFFB36B) else Color.White.copy(alpha = 0.86f),
            fontFamily = FontFamily.Monospace,
            fontSize = 9.sp,
            fontWeight = FontWeight.Black,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            letterSpacing = 0.sp,
            modifier = Modifier.padding(top = 5.dp),
        )
        Text(
            text = profile.vibeLabel,
            color = if (selected) Color(0xFF6DFF8F) else Color.White.copy(alpha = 0.42f),
            fontFamily = FontFamily.Monospace,
            fontSize = 7.sp,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            letterSpacing = 0.sp,
        )
    }
}

private fun createProcessedThumbnail(profile: CameraProfile): Bitmap {
    val width = 132
    val height = 82
    val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    val canvas = AndroidCanvas(bitmap)
    val paint = Paint(Paint.ANTI_ALIAS_FLAG)

    paint.shader = LinearGradient(
        0f,
        0f,
        width.toFloat(),
        height.toFloat(),
        AndroidColor.rgb(225, 226, 217),
        AndroidColor.rgb(30, 36, 50),
        Shader.TileMode.CLAMP,
    )
    canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), paint)

    paint.shader = RadialGradient(
        width * 0.76f,
        height * 0.22f,
        width * 0.22f,
        AndroidColor.argb(255, 255, 250, 210),
        AndroidColor.argb(0, 255, 250, 210),
        Shader.TileMode.CLAMP,
    )
    canvas.drawCircle(width * 0.76f, height * 0.22f, width * 0.30f, paint)

    paint.shader = null
    paint.color = AndroidColor.rgb(216, 160, 128)
    canvas.drawOval(width * 0.18f, height * 0.26f, width * 0.48f, height * 0.77f, paint)
    paint.color = AndroidColor.rgb(30, 30, 34)
    canvas.drawRect(0f, height * 0.62f, width.toFloat(), height.toFloat(), paint)
    paint.color = AndroidColor.rgb(72, 92, 118)
    canvas.drawRect(width * 0.56f, height * 0.42f, width * 0.92f, height * 0.86f, paint)
    paint.color = AndroidColor.rgb(238, 242, 228)
    canvas.drawCircle(width * 0.70f, height * 0.50f, 7f, paint)
    paint.color = AndroidColor.rgb(180, 205, 220)
    canvas.drawRect(width * 0.02f, height * 0.10f, width * 0.18f, height * 0.54f, paint)

    val processed = RetroBitmapProcessor.process(
        input = bitmap,
        profile = profile.copy(exportResolution = null),
        timestampStyle = null,
    )
    if (processed !== bitmap) bitmap.recycle()
    return processed
}
