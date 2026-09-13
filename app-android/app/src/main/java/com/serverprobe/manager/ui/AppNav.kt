package com.serverprobe.manager.ui

import androidx.compose.runtime.Composable
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.serverprobe.manager.ui.addprobe.AddProbeScreen
import com.serverprobe.manager.ui.detail.DetailScreen
import com.serverprobe.manager.ui.home.HomeScreen
import com.serverprobe.manager.ui.settings.SettingsScreen
import com.serverprobe.manager.ui.ssh.SshFormScreen
import com.serverprobe.manager.ui.ssh.TerminalScreen

object Routes {
    const val HOME = "home"
    const val ADD_PROBE = "probe/add"
    const val EDIT_PROBE = "probe/edit/{id}"
    const val DETAIL = "probe/{id}"
    const val ADD_SSH = "ssh/add"
    const val EDIT_SSH = "ssh/edit/{id}"
    const val TERMINAL = "terminal/{sshId}"
    const val SETTINGS = "settings"

    fun editProbe(id: Long) = "probe/edit/$id"
    fun detail(id: Long) = "probe/$id"
    fun editSsh(id: Long) = "ssh/edit/$id"
    fun terminal(sshId: Long) = "terminal/$sshId"
}

@Composable
fun AppNav() {
    val nav = rememberNavController()
    NavHost(navController = nav, startDestination = Routes.HOME) {
        composable(Routes.HOME) {
            HomeScreen(
                onAddProbe = { nav.navigate(Routes.ADD_PROBE) },
                onAddSsh = { nav.navigate(Routes.ADD_SSH) },
                onProbeDetail = { nav.navigate(Routes.detail(it)) },
                onEditProbe = { nav.navigate(Routes.editProbe(it)) },
                onEditSsh = { nav.navigate(Routes.editSsh(it)) },
                onTerminal = { nav.navigate(Routes.terminal(it)) },
                onSettings = { nav.navigate(Routes.SETTINGS) },
            )
        }
        composable(Routes.ADD_PROBE) {
            AddProbeScreen(onDone = { nav.popBackStack() })
        }
        composable(
            Routes.EDIT_PROBE,
            arguments = listOf(navArgument("id") { type = NavType.LongType }),
        ) {
            AddProbeScreen(editId = it.arguments?.getLong("id") ?: 0L, onDone = { nav.popBackStack() })
        }
        composable(
            Routes.DETAIL,
            arguments = listOf(navArgument("id") { type = NavType.LongType }),
        ) {
            DetailScreen(
                hostId = it.arguments?.getLong("id") ?: 0L,
                onBack = { nav.popBackStack() },
                onEdit = { nav.navigate(Routes.editProbe(it)) },
                onTerminal = { nav.navigate(Routes.terminal(it)) },
                onAddSsh = { nav.navigate(Routes.ADD_SSH) },
            )
        }
        composable(Routes.ADD_SSH) {
            SshFormScreen(onDone = { nav.popBackStack() })
        }
        composable(
            Routes.EDIT_SSH,
            arguments = listOf(navArgument("id") { type = NavType.LongType }),
        ) {
            SshFormScreen(editId = it.arguments?.getLong("id") ?: 0L, onDone = { nav.popBackStack() })
        }
        composable(
            Routes.TERMINAL,
            arguments = listOf(navArgument("sshId") { type = NavType.LongType }),
        ) {
            TerminalScreen(sshId = it.arguments?.getLong("sshId") ?: 0L, onClose = { nav.popBackStack() })
        }
        composable(Routes.SETTINGS) {
            SettingsScreen(onBack = { nav.popBackStack() })
        }
    }
}
