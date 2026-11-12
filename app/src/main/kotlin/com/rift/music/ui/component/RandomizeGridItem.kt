/**
 * Napster (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.rift.music.ui.component

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.rift.music.R
import com.rift.music.constants.ThumbnailRoundedShape
import com.rift.music.ui.component.shapes.ContinuousRoundedRectangle
import com.rift.music.ui.theme.AppleTokens

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun RandomizeGridItem(
    isLoading: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    cornerRadiusDp: Int = 0,
) {
    val shape =
        if (cornerRadiusDp > 0) ContinuousRoundedRectangle(cornerRadiusDp.dp) else ThumbnailRoundedShape

    // When isLoading is true, multiplier goes to 0 (moving dots to center)
    // When isLoading is false, multiplier goes to 1 (moving dots to corners)
    val dotOffsetMultiplier by animateFloatAsState(
        targetValue = if (isLoading) 0f else 1f,
        animationSpec = tween(durationMillis = 600),
        label = "dotOffset"
    )

    val loadingAlpha by animateFloatAsState(
        targetValue = if (isLoading) 1f else 0f,
        animationSpec = tween(durationMillis = 400),
        label = "loadingAlpha"
    )

    Column(
        modifier = modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            modifier = Modifier
                .weight(1f)
                .aspectRatio(1f)
                .clip(shape)
                .background(MaterialTheme.colorScheme.secondaryContainer)
                .clickable(onClick = onClick),
            contentAlignment = Alignment.Center
        ) {
            // Die Dots (5-pattern)
            val dotColor = MaterialTheme.colorScheme.onSecondaryContainer
            val dotSize = 14.dp
            val padding = 24.dp

            // Top Left
            Box(
                modifier = Modifier
                    .align(Alignment.Center)
                    .offset { IntOffset((-padding * dotOffsetMultiplier).roundToPx(), (-padding * dotOffsetMultiplier).roundToPx()) }
                    .size(dotSize)
                    .clip(CircleShape)
                    .background(dotColor)
            )
            // Top Right
            Box(
                modifier = Modifier
                    .align(Alignment.Center)
                    .offset { IntOffset((padding * dotOffsetMultiplier).roundToPx(), (-padding * dotOffsetMultiplier).roundToPx()) }
                    .size(dotSize)
                    .clip(CircleShape)
                    .background(dotColor)
            )
            // Center
            Box(
                modifier = Modifier
                    .align(Alignment.Center)
                    .size(dotSize)
                    .clip(CircleShape)
                    .background(dotColor)
            )
            // Bottom Left
            Box(
                modifier = Modifier
                    .align(Alignment.Center)
                    .offset { IntOffset((-padding * dotOffsetMultiplier).roundToPx(), (padding * dotOffsetMultiplier).roundToPx()) }
                    .size(dotSize)
                    .clip(CircleShape)
                    .background(dotColor)
            )
            // Bottom Right
            Box(
                modifier = Modifier
                    .align(Alignment.Center)
                    .offset { IntOffset((padding * dotOffsetMultiplier).roundToPx(), (padding * dotOffsetMultiplier).roundToPx()) }
                    .size(dotSize)
                    .clip(CircleShape)
                    .background(dotColor)
            )

            // Loading Indicator overlay
            if (loadingAlpha > 0f) {
                Box(modifier = Modifier.graphicsLayer { alpha = loadingAlpha }) {
                    LoadingIndicator(
                        modifier = Modifier.size(48.dp),
                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                    )
                }
            }
        }

        Text(
            text = stringResource(R.string.random),
            fontSize = AppleTokens.Caption,
            lineHeight = AppleTokens.CaptionLineHeight,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 2,
            textAlign = TextAlign.Center,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .padding(top = AppleTokens.TextGap * 3)
                .fillMaxWidth(),
        )
    }
}
