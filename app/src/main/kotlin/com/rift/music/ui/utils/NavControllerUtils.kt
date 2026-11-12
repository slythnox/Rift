/**
 * Napster (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.rift.music.ui.utils

import androidx.navigation.NavController
import com.rift.music.ui.navigation.Screens

fun NavController.backToMain() {
    while (previousBackStackEntry != null &&
        currentBackStackEntry?.destination?.route !in Screens.MainRoutes
    ) {
        popBackStack()
    }
}
