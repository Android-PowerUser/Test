package com.google.ai.sample.common.ui

import androidx.compose.animation.core.*
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.offset
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.scale // Import for scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight // Import for FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay

@Composable
fun AnimatedAppTitle(
    appName: String = "Screen Operator",
    modifier: Modifier = Modifier
) {
    val letters = appName.toList()

    // Define a list of distinct dark colors for the letters
    // These colors should offer good contrast on both light (0xFFFFFBFE) and dark (0xFF1C1B1F) backgrounds.
    val darkLetterColors = listOf(
        Color(0xFF0D47A1), // Dark Blue
        Color(0xFF4A148C), // Dark Purple
        Color(0xFF004D40), // Dark Teal
        Color(0xFFBF360C), // Dark Orange/Brown
        Color(0xFF263238), // Dark Blue Grey
        Color(0xFF1B5E20), // Dark Green
        Color(0xFF3E2723), // Dark Brown
        Color(0xFF880E4F), // Dark Pink/Magenta
        Color(0xFF01579B), // Another Dark Blue
        Color(0xFF311B92), // Deep Purple
        Color(0xFF006064), // Dark Cyan
        Color(0xFFD84315), // Deep Orange
        Color(0xFF37474F), // Another Blue Grey
        Color(0xFF2E7D32), // Medium Dark Green
        Color(0xFF4E342E), // Another Dark Brown
        Color(0xFFA04000), // Saturated Brown
        Color(0xFF1A237E), // Indigo
    )

    Row(modifier = modifier) {
        letters.forEachIndexed { index, letter ->
            var visible by remember { mutableStateOf(false) }

            val alpha by animateFloatAsState(
                targetValue = if (visible) 1f else 0f,
                animationSpec = tween(durationMillis = 500, easing = LinearOutSlowInEasing), // Added easing
                label = "alpha_anim_${index}"
            )

            val offsetY by animateDpAsState(
                targetValue = if (visible) 0.dp else 20.dp,
                animationSpec = tween(durationMillis = 500, easing = LinearOutSlowInEasing), // Added easing
                label = "offsetY_anim_${index}"
            )

            val scale by animateFloatAsState(
                targetValue = if (visible) 1f else 0.8f, // Scale from 0.8f to 1f
                animationSpec = tween(durationMillis = 500, easing = LinearOutSlowInEasing), // Added easing
                label = "scale_anim_${index}"
            )

            LaunchedEffect(key1 = Unit) {
                delay(index * 120L) // Slightly increased delay for better effect with scale
                visible = true
            }

            Text(
                text = letter.toString(),
                fontSize = 22.sp,
                fontWeight = FontWeight.SemiBold, // Changed to SemiBold
                color = darkLetterColors[index % darkLetterColors.size],
                modifier = Modifier
                    .alpha(alpha)
                    .scale(scale) // Apply scale
                    .offset(y = offsetY)
            )
        }
    }
}
