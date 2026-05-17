package com.srihari.vintix.ui.camera

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

import androidx.compose.foundation.gestures.snapping.rememberSnapFlingBehavior
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.LaunchedEffect

@Composable
fun ProfileSelector(
    profiles: List<String>,
    selectedProfile: String,
    onProfileSelected: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val listState = rememberLazyListState()
    val snappingBehavior = rememberSnapFlingBehavior(lazyListState = listState)

    LaunchedEffect(selectedProfile) {
        val index = profiles.indexOf(selectedProfile)
        if (index >= 0) {
            listState.animateScrollToItem(index)
        }
    }

    LazyRow(
        state = listState,
        flingBehavior = snappingBehavior,
        modifier = modifier.height(60.dp),
        contentPadding = PaddingValues(horizontal = 150.dp), // Center the selected item
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        items(profiles) { profileName ->
            val isSelected = profileName == selectedProfile
            
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(2.dp))
                    .background(
                        if (isSelected) Color(0xFFFF8A1F) // Vintix Accent
                        else Color.White.copy(alpha = 0.08f)
                    )
                    .clickable { onProfileSelected(profileName) }
                    .padding(horizontal = 14.dp, vertical = 8.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = profileName.uppercase(),
                    color = if (isSelected) Color.Black else Color.White.copy(alpha = 0.7f),
                    fontFamily = FontFamily.Monospace,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 1.sp
                )
            }
        }
    }
}
