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
import java.text.SimpleDateFormat
import java.util.*

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
            .background(Color.Black)
    ) {
        if (photos.isEmpty()) {
            Text(
                text = "No photos yet",
                color = Color.White.copy(alpha = 0.5f),
                modifier = Modifier.align(Alignment.Center)
            )
            
            TextButton(
                onClick = onNavigateBack,
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(16.dp)
                    .statusBarsPadding()
            ) {
                Text(
                    text = "[ BACK ]",
                    color = Color.White,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    fontSize = 14.sp
                )
            }
            return
        }

        val pagerState = rememberPagerState(pageCount = { photos.size })

        HorizontalPager(
            state = pagerState,
            modifier = Modifier.fillMaxSize()
        ) { page ->
            val photo = photos[page]
            AsyncImage(
                model = photo.uri,
                contentDescription = "Photo taken with ${photo.profileName}",
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Fit
            )
        }

        // Top bar
        Row(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth()
                .background(Color.Black.copy(alpha = 0.4f))
                .statusBarsPadding()
                .padding(horizontal = 8.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            TextButton(onClick = onNavigateBack) {
                Text(
                    text = "[ BACK ]",
                    color = Color.White,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    fontSize = 14.sp
                )
            }

            TextButton(
                onClick = {
                    val currentPhoto = photos[pagerState.currentPage]
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
                    color = Color.White,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    fontSize = 14.sp
                )
            }
        }

        // Bottom info overlay (retro style)
        val currentPhoto = photos[pagerState.currentPage]
        Column(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .fillMaxWidth()
                .background(Color.Black.copy(alpha = 0.4f))
                .navigationBarsPadding()
                .padding(16.dp)
        ) {
            val dateFormat = SimpleDateFormat("yyyy/MM/dd HH:mm", Locale.US)
            val dateStr = dateFormat.format(Date(currentPhoto.dateTaken))
            
            Text(
                text = dateStr,
                color = Color(0xFFFF9800), // Classic digital orange
                fontFamily = FontFamily.Monospace,
                fontSize = 14.sp
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "PROFILE: ${currentPhoto.profileName.uppercase(Locale.US)}",
                color = Color.White,
                fontFamily = FontFamily.Monospace,
                fontSize = 12.sp,
                letterSpacing = 1.sp
            )
        }
    }
}
