package com.google.ai.sample.common.ui

import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.SmallTopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SharedTopAppBar(
    modifier: Modifier = Modifier
) {
    SmallTopAppBar(
        title = {
            AnimatedAppTitle() // Uses the default app name "Screen Operator"
        },
        colors = TopAppBarDefaults.smallTopAppBarColors(
            containerColor = Color.Transparent // Make AppBar transparent
        ),
        modifier = modifier
        // SmallTopAppBar automatically handles window insets for the status bar
        // when used correctly within a Scaffold or if windowInsets are provided.
        // No explicit padding needed here if relying on its default behavior.
    )
}
