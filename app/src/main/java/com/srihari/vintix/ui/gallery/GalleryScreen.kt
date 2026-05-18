package com.srihari.vintix.ui.gallery

import android.content.Intent
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import coil.request.ImageRequest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun GalleryScreen(
    viewModel: GalleryViewModel = viewModel(),
    onNavigateBack: () -> Unit,
) {
    val photos by viewModel.photos.collectAsState()
    val context = LocalContext.current

    LaunchedEffect(Unit) {
        viewModel.loadPhotos()
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF050505)),
    ) {
        if (photos.isEmpty()) {
            EmptyGallery(onNavigateBack = onNavigateBack, modifier = Modifier.align(Alignment.Center))
            return
        }

        val pagerState = rememberPagerState(pageCount = { photos.size })

        HorizontalPager(
            state = pagerState,
            modifier = Modifier.fillMaxSize(),
            pageSpacing = 16.dp,
        ) { page ->
            val photo = photos[page]
            val imageRequest = remember(photo.uri) {
                ImageRequest.Builder(context)
                    .data(photo.uri)
                    .crossfade(true)
                    .allowHardware(true)
                    .build()
            }
            AsyncImage(
                model = imageRequest,
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Fit,
            )
        }

        Row(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth()
                .background(Color.Black.copy(alpha = 0.72f))
                .statusBarsPadding()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(onClick = onNavigateBack) {
                Text("BACK", color = Color.White, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "${pagerState.currentPage + 1} / ${photos.size}",
                    color = Color.White.copy(alpha = 0.72f),
                    fontFamily = FontFamily.Monospace,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                )
                Spacer(modifier = Modifier.width(8.dp))
                TextButton(
                    onClick = {
                        val currentPhoto = photos.getOrNull(pagerState.currentPage) ?: return@TextButton
                        viewModel.deletePhoto(currentPhoto.uri)
                    },
                ) {
                    Text("DELETE", color = Color(0xFFFF6B6B), fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                }
                TextButton(
                    onClick = {
                        val currentPhoto = photos.getOrNull(pagerState.currentPage) ?: return@TextButton
                        val shareIntent = Intent().apply {
                            action = Intent.ACTION_SEND
                            putExtra(Intent.EXTRA_STREAM, currentPhoto.uri)
                            type = "image/jpeg"
                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        }
                        context.startActivity(Intent.createChooser(shareIntent, "Share Vintix Photo"))
                    },
                ) {
                    Text("SHARE", color = Color(0xFFFF8A1F), fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                }
            }
        }

        val safeIndex = pagerState.currentPage.coerceIn(0, (photos.size - 1).coerceAtLeast(0))
        photos.getOrNull(safeIndex)?.let { currentPhoto ->
            GalleryInfoPanel(
                photo = currentPhoto,
                modifier = Modifier.align(Alignment.BottomStart),
            )
        }
    }
}

@Composable
private fun EmptyGallery(onNavigateBack: () -> Unit, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = "NO IMAGES",
            color = Color.White.copy(alpha = 0.32f),
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold,
            fontSize = 14.sp,
        )
        Spacer(modifier = Modifier.height(24.dp))
        TextButton(onClick = onNavigateBack) {
            Text("BACK", color = Color.White, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun GalleryInfoPanel(photo: VintixPhoto, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(Color.Black.copy(alpha = 0.74f))
            .navigationBarsPadding()
            .padding(24.dp),
    ) {
        val dateFormat = SimpleDateFormat("yyyy/MM/dd HH:mm", Locale.US)
        val dateText = dateFormat.format(Date(photo.dateTaken))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = dateText,
                color = Color(0xFF6DFF8F),
                fontFamily = FontFamily.Monospace,
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold,
            )
            Text(
                text = "VINTIX OS v1.0",
                color = Color.White.copy(alpha = 0.42f),
                fontFamily = FontFamily.Monospace,
                fontSize = 10.sp,
            )
        }

        Spacer(modifier = Modifier.height(8.dp))

        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .border(1.dp, Color.White.copy(alpha = 0.45f), RoundedCornerShape(2.dp))
                    .padding(horizontal = 6.dp, vertical = 2.dp),
            ) {
                Text(
                    text = photo.profileName.uppercase(Locale.US),
                    color = Color.White,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                )
            }
            Spacer(modifier = Modifier.width(12.dp))
            Text(
                text = "JPEG EXPORT",
                color = Color.White.copy(alpha = 0.52f),
                fontFamily = FontFamily.Monospace,
                fontSize = 10.sp,
            )
        }
    }
}
