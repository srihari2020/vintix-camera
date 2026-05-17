package com.srihari.vintix.ui.gallery

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import coil.request.CachePolicy
import coil.request.ImageRequest
import coil.size.Precision
import java.text.SimpleDateFormat
import java.util.*
import kotlin.math.absoluteValue

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun GalleryScreen(
    viewModel: GalleryViewModel = viewModel(),
    onNavigateBack: () -> Unit
) {
    val photos by viewModel.photos.collectAsState()
    val context = LocalContext.current

    LaunchedEffect(Unit) {
        viewModel.loadPhotos()
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF050505))
    ) {
        if (photos.isEmpty()) {
            Column(
                modifier = Modifier.align(Alignment.Center),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = "NO IMAGES",
                    color = Color.White.copy(alpha = 0.3f),
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    fontSize = 14.sp
                )
                Spacer(modifier = Modifier.height(24.dp))
                TextButton(onClick = onNavigateBack) {
                    Text("[ BACK ]", color = Color.White, fontFamily = FontFamily.Monospace)
                }
            }
            return
        }

        val pagerState = rememberPagerState(pageCount = { photos.size })

        HorizontalPager(
            state = pagerState,
            modifier = Modifier.fillMaxSize(),
            pageSpacing = 16.dp
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
                contentScale = ContentScale.Fit
            )
        }

        // Top Bar (Matte Overlay)
        Row(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth()
                .background(Color.Black.copy(alpha = 0.6f))
                .statusBarsPadding()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onNavigateBack) {
                Text("←", color = Color.White, fontSize = 24.sp)
            }
            
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "${pagerState.currentPage + 1} / ${photos.size}",
                    color = Color.White.copy(alpha = 0.7f),
                    fontFamily = FontFamily.Monospace,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.width(16.dp))
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
                    }
                ) {
                    Text(
                        text = "[ SHARE ]",
                        color = Color(0xFFFF8A1F),
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold,
                        fontSize = 12.sp
                    )
                }
            }
        }

        // Bottom Info HUD
        val safeIndex = pagerState.currentPage.coerceIn(0, (photos.size - 1).coerceAtLeast(0))
        val currentPhoto = photos.getOrNull(safeIndex)
        if (currentPhoto != null) {
            Column(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .fillMaxWidth()
                    .background(Color.Black.copy(alpha = 0.7f))
                    .navigationBarsPadding()
                    .padding(24.dp)
            ) {
                val dateFormat = SimpleDateFormat("yyyy/MM/dd HH:mm", Locale.US)
                val dateStr = dateFormat.format(Date(currentPhoto.dateTaken))
                
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = dateStr,
                        color = Color(0xFF00FF00).copy(alpha = 0.8f),
                        fontFamily = FontFamily.Monospace,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold
                    )
                    
                    Text(
                        text = "VINTIX OS v1.0",
                        color = Color.White.copy(alpha = 0.4f),
                        fontFamily = FontFamily.Monospace,
                        fontSize = 10.sp
                    )
                }
                
                Spacer(modifier = Modifier.height(8.dp))
                
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .border(1.dp, Color.White.copy(alpha = 0.5f), RoundedCornerShape(2.dp))
                            .padding(horizontal = 6.dp, vertical = 2.dp)
                    ) {
                        Text(
                            text = currentPhoto.profileName.uppercase(Locale.US),
                            color = Color.White,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                    
                    Spacer(modifier = Modifier.width(12.dp))
                    
                    Text(
                        text = "100% QUALITY",
                        color = Color.White.copy(alpha = 0.5f),
                        fontFamily = FontFamily.Monospace,
                        fontSize = 10.sp
                    )
                }
            }
        }
    }
}
