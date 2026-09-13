package com.serverprobe.manager.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.serverprobe.manager.ui.addprobe.AddProbeScreen
import com.serverprobe.manager.ui.detail.DetailScreen
import com.serverprobe.manager.ui.home.HomeScreen
import com.serverprobe.manager.ui.remote.RemoteScreen
import com.serverprobe.manager.ui.settings.SettingsScreen
import com.serverprobe.manager.ui.ssh.SshFormScreen
import com.serverprobe.manager.ui.ssh.TerminalScreen

object Routes {
    const val HOME = "home"
    const val REMOTE = "remote"
    const val SETTINGS = "settings"
    const val ADD_PROBE = "probe/add"
    const val EDIT_PROBE = "probe/edit/{id}"
    const val DETAIL = "probe/{id}"
    const val ADD_SSH = "ssh/add"
    const val EDIT_SSH = "ssh/edit/{id}"
    const val TERMINAL = "terminal/{sshId}"

    fun editProbe(id: Long) = "probe/edit/$id"
    fun detail(id: Long) = "probe/$id"
    fun editSsh(id: Long) = "ssh/edit/$id"
    fun terminal(sshId: Long) = "terminal/$sshId"

    val topLevel = setOf(HOME, REMOTE, SETTINGS)
}

private data class Tab(val route: String, val label: String, val icon: ImageVector)

private val TABS = listOf(
    Tab(Routes.HOME, "管理", Icons.Filled.Dns),
    Tab(Routes.REMOTE, "远程", Icons.Filled.Terminal),
    Tab(Routes.SETTINGS, "设置", Icons.Filled.Settings),
)

@Composable
fun AppNav() {
    val nav = rememberNavController()
    val backStack by nav.currentBackStackEntryAsState()
    val currentRoute = backStack?.destination?.route

    Column(Modifier.fillMaxSize()) {
        NavHost(
            navController = nav,
            startDestination = Routes.HOME,
            modifier = Modifier.weight(1f),
        ) {
            composable(Routes.HOME) {
                HomeScreen(
                    onAddProbe = { nav.navigate(Routes.ADD_PROBE) },
                    onAddSsh = { nav.navigate(Routes.ADD_SSH) },
                    onProbeDetail = { nav.navigate(Routes.detail(it)) },
                    onEditProbe = { nav.navigate(Routes.editProbe(it)) },
                    onEditSsh = { nav.navigate(Routes.editSsh(it)) },
                    onTerminal = { nav.navigate(Routes.terminal(it)) },
                )
            }
            composable(Routes.REMOTE) {
                RemoteScreen()
            }
            composable(Routes.SETTINGS) {
                SettingsScreen(showBack = false)
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
        }

        if (currentRoute in Routes.topLevel) {
            NavigationBar {
                TABS.forEach { tab ->
                    NavigationBarItem(
                        selected = currentRoute == tab.route,
                        onClick = {
                            nav.navigate(tab.route) {
                                popUpTo(nav.graph.findStartDestination().id) { saveState = true }
                                launchSingleTop = true
                                restoreState = true
                            }
                        },
                        icon = { Icon(tab.icon, contentDescription = tab.label) },
                        label = { Text(tab.label) },
                    )
                }
            }
        }
    }
}
