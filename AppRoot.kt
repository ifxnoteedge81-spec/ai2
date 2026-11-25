package com.personal.aicomputer.ui

import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Dashboard
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.personal.aicomputer.ServiceLocator
import kotlinx.coroutines.launch

private data class Dest(val route: String, val title: String, val icon: ImageVector)

private val bottomDests = listOf(
    Dest("home", "Home", Icons.Filled.Dashboard),
    Dest("agent", "Agent", Icons.Filled.SmartToy),
    Dest("terminal", "Terminal", Icons.Filled.Terminal),
    Dest("browser", "Browser", Icons.Filled.Language),
    Dest("files", "Files", Icons.Filled.Folder),
)
private val drawerDests = listOf(
    Dest("memory", "Agent Memory", Icons.Filled.Memory),
    Dest("history", "Task History", Icons.Filled.History),
    Dest("models", "AI Model Settings", Icons.Filled.Key),
    Dest("resources", "Resource Manager", Icons.Filled.Speed),
    Dest("permissions", "Permissions & Security", Icons.Filled.Security),
    Dest("about", "About & Roadmap", Icons.Filled.Info),
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppRoot() {
    val nav = rememberNavController()
    val drawer = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    val backStack by nav.currentBackStackEntryAsState()
    val route = backStack?.destination?.route ?: "home"
    val all = bottomDests + drawerDests

    fun go(r: String) {
        nav.navigate(r) {
            popUpTo(nav.graph.findStartDestination().id) { saveState = true }
            launchSingleTop = true
            restoreState = true
        }
    }

    ModalNavigationDrawer(
        drawerState = drawer,
        drawerContent = {
            ModalDrawerSheet {
                Text("Personal AI Computer", modifier = Modifier.padding(20.dp), style = androidx.compose.material3.MaterialTheme.typography.titleMedium)
                (bottomDests + drawerDests).forEach { d ->
                    NavigationDrawerItem(
                        label = { Text(d.title) }, icon = { Icon(d.icon, null) },
                        selected = route == d.route,
                        onClick = { scope.launch { drawer.close() }; go(d.route) },
                        modifier = Modifier.padding(horizontal = 12.dp),
                    )
                }
            }
        },
    ) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text(all.firstOrNull { it.route == route }?.title ?: "Personal AI Computer") },
                    navigationIcon = { IconButton(onClick = { scope.launch { drawer.open() } }) { Icon(Icons.Filled.Menu, "Menu") } },
                )
            },
            bottomBar = {
                NavigationBar {
                    bottomDests.forEach { d ->
                        NavigationBarItem(selected = route == d.route, onClick = { go(d.route) }, icon = { Icon(d.icon, d.title) }, label = { Text(d.title) })
                    }
                }
            },
        ) { pad ->
            NavHost(nav, startDestination = "home", modifier = Modifier.padding(pad)) {
                composable("home") { HomeScreen(onOpen = ::go) }
                composable("agent") { CommandScreen() }
                composable("terminal") { TerminalScreen() }
                composable("browser") { BrowserScreen() }
                composable("files") { FilesScreen() }
                composable("memory") { MemoryScreen() }
                composable("history") { HistoryScreen(onOpenTask = { id -> ServiceLocator.controller.select(id); go("agent") }) }
                composable("models") { ModelSettingsScreen() }
                composable("resources") { ResourceScreen() }
                composable("permissions") { PermissionsScreen() }
                composable("about") { AboutScreen() }
            }
        }
    }
    ApprovalDialog()
}
