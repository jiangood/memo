package fumi.day.literalmemo.ui

import androidx.compose.runtime.Composable
import androidx.navigation.compose.rememberNavController
import fumi.day.literalmemo.ui.navigation.NavGraph
import fumi.day.literalmemo.ui.theme.LiteralMemoTheme

@Composable
fun App(
    sharedText: String? = null,
    onSharedTextConsumed: () -> Unit = {}
) {
    LiteralMemoTheme {
        val navController = rememberNavController()
        NavGraph(
            navController = navController,
            sharedText = sharedText,
            onSharedTextConsumed = onSharedTextConsumed
        )
    }
}
