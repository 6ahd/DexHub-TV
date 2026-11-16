package com.nuvio.tv.ui.v2.navigation

import androidx.activity.compose.BackHandler
import androidx.annotation.DrawableRes
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Button
import androidx.tv.material3.ButtonDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Icon
import androidx.tv.material3.Text
import com.nuvio.tv.DrawerItem
import com.nuvio.tv.LocalContentFocusRequester
import com.nuvio.tv.LocalSidebarExpanded
import com.nuvio.tv.R
import com.nuvio.tv.ui.navigation.Screen
import com.nuvio.tv.ui.screens.home.DexHomeTypeFilter
import com.nuvio.tv.ui.v2.components.AvatarModel
import com.nuvio.tv.ui.v2.components.NuvioAvatar
import kotlinx.coroutines.delay
import java.util.Date

val LocalV2TopChrome = staticCompositionLocalOf { false }

// skin.dexhub (3.28.228) colours for the bar: focus is white, not the accent (dh.kodifocus unset).
private val FocusFill = Color(0xF2FFFFFF)
private val OnFocus = Color(0xFF272727)
private val SelectedFill = Color(0x2EFFFFFF)
private val IdleIcon = Color(0xB3FFFFFF)
private val SurfaceTint = Color(0xFF0B0E1C)

/** Routes Dex Hub draws as icons at the right end of the bar instead of as tabs. */
private val TrailingRoutes = setOf(Screen.Search.route, Screen.Settings.route)

/** A tab after which the skin draws its divider (Add-ons, then Discover...). */
private const val DividerAfter = "addon_manager"

private data class DexTabSpec(val route: String, val label: String, @DrawableRes val icon: Int, @DrawableRes val iconOn: Int)

/**
 * Dex Hub's top bar, after skin.dexhub's Home (Includes_Home.xml, ids 9005, 9000, 9001, 9510):
 * a frosted pill across the top; the profile or Dex Hub wordmark on the left; icon tabs where
 * the current page is an 18 % white pill with its name and the focused tab a white pill with a
 * dark icon and name; then Edit, Search, Menu and Power, and the bell and clock pill.
 */
@Composable
@OptIn(ExperimentalTvMaterial3Api::class)
fun V2TopNavigation(
    navigationContent: @Composable (Boolean) -> Unit,
    currentRoute: String?,
    rootRoutes: Set<String>,
    items: List<DrawerItem>,
    selectedRoute: String?,
    profile: AvatarModel?,
    onNavigate: (String) -> Unit,
    onSwitchProfile: () -> Unit,
    onExit: () -> Unit
) {
    val visible = currentRoute in rootRoutes
    val contentFocus = remember { FocusRequester() }
    val tabs = dexTabs(items)
    val search = items.firstOrNull { it.route == Screen.Search.route }
    val settings = items.firstOrNull { it.route == Screen.Settings.route }
    val typeFilter by DexHomeTypeFilter.type.collectAsState()
    val activeRoute = if (selectedRoute == Screen.Home.route) DexHomeTypeFilter.routeFor(typeFilter) ?: selectedRoute else selectedRoute
    val routes = tabs.map { it.route } + listOfNotNull(search?.route, settings?.route)
    val routeFocus = remember(routes) { routes.associateWith { FocusRequester() } }
    val profileFocus = remember { FocusRequester() }
    var lastRoute by remember { mutableStateOf(activeRoute) }
    var navFocused by remember { mutableStateOf(false) }
    var returnToContent by remember { mutableStateOf(false) }
    val entryFocus = routeFocus[lastRoute] ?: routeFocus[activeRoute] ?: routeFocus.values.firstOrNull() ?: profileFocus

    // Destination BackHandlers are composed later and retain priority for sheets/settings.
    BackHandler(enabled = visible) {
        if (navFocused) onExit() else entryFocus.requestFocus()
    }
    LaunchedEffect(returnToContent, currentRoute) {
        if (returnToContent) {
            repeat(2) { withFrameNanos { } }
            contentFocus.requestFocus()
            returnToContent = false
        }
    }
    fun open(route: String, thenContent: Boolean) {
        val type = DexHomeTypeFilter.typeFor(route)
        DexHomeTypeFilter.set(type)
        onNavigate(if (type != null) Screen.Home.route else route)
        if (thenContent) returnToContent = true
    }
    // As on the skin (homeui_nav_focus_open), resting on Home, Movies or Series opens it;
    // those three are one page, so this costs no navigation. Other tabs open on OK.
    var focusedTab by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(focusedTab) {
        val route = focusedTab ?: return@LaunchedEffect
        if (route != activeRoute && selectedRoute == Screen.Home.route &&
            (route == Screen.Home.route || DexHomeTypeFilter.typeFor(route) != null)) {
            delay(350L)
            open(route, thenContent = false)
        }
    }
    Box(Modifier.fillMaxSize()) {
        Box(Modifier.fillMaxSize().padding(top = if (visible) 54.dp else 0.dp)
            .focusRequester(contentFocus).focusRestorer()
            .focusProperties {
                this.onExit = {
                    if (visible && requestedFocusDirection == FocusDirection.Up) entryFocus.requestFocus()
                }
            }.focusGroup()) {
            CompositionLocalProvider(LocalContentFocusRequester provides contentFocus,
                LocalSidebarExpanded provides navFocused,
                LocalV2TopChrome provides visible) {
                navigationContent(true)
            }
        }
        if (!visible) return@Box
        // grad_top.png tinted with the surface colour, 250 px tall.
        Box(Modifier.align(Alignment.TopCenter).fillMaxWidth().height(125.dp).background(Brush.verticalGradient(
            0f to SurfaceTint.copy(alpha = .85f), .2f to SurfaceTint.copy(alpha = .67f),
            .4f to SurfaceTint.copy(alpha = .40f), .6f to SurfaceTint.copy(alpha = .31f),
            .8f to SurfaceTint.copy(alpha = .12f), 1f to Color.Transparent)))
        // glass_bar.png: a frosted pill, 1824 x 76 at (48, 22).
        Box(Modifier.align(Alignment.TopStart).padding(horizontal = 24.dp).offset(y = 11.dp).fillMaxWidth().height(38.dp)
            .background(Brush.verticalGradient(listOf(Color(0x527F8083), Color(0x4A6A6B6F), Color(0x3D45464B))), RoundedCornerShape(19.dp))
            .border(1.dp, Brush.verticalGradient(listOf(Color(0xCCF0F0F0), Color(0x69A8A9AB))), RoundedCornerShape(19.dp)))
        Row(Modifier.align(Alignment.TopStart).padding(start = 35.dp, end = 35.dp).offset(y = 17.dp).fillMaxWidth().height(26.dp)
            .onFocusChanged { navFocused = it.hasFocus }.focusGroup(),
            verticalAlignment = Alignment.CenterVertically) {
            ProfileButton(profile, Modifier.focusRequester(profileFocus).focusProperties { down = contentFocus },
                onClick = onSwitchProfile)
            Spacer(Modifier.width(2.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(1.5.dp), verticalAlignment = Alignment.CenterVertically) {
                tabs.forEach { tab ->
                    DexTab(tab, isSelected = tab.route == activeRoute,
                        modifier = Modifier.focusRequester(routeFocus.getValue(tab.route))
                            .focusProperties { down = contentFocus },
                        onFocused = { lastRoute = tab.route; focusedTab = tab.route },
                        onClick = { open(tab.route, thenContent = true) })
                    if (tab.route == DividerAfter) {
                        Box(Modifier.padding(horizontal = 2.5.dp).width(1.dp).height(12.dp).background(Color(0x40FFFFFF)))
                    }
                }
            }
            Spacer(Modifier.weight(1f))
            // Edit Home, Search, Menu, Power (list 9001, 76 px apart).
            BarIcon(R.drawable.dexnav_edit, stringResource(R.string.dex_bar_edit_home),
                Modifier.focusProperties { down = contentFocus }) { open(Screen.LayoutSettings.route, thenContent = false) }
            search?.let { item ->
                BarIcon(R.drawable.dexnav_search, item.label,
                    Modifier.focusRequester(routeFocus.getValue(item.route)).focusProperties { down = contentFocus },
                    onFocused = { lastRoute = item.route }) { open(item.route, thenContent = true) }
            }
            settings?.let { item ->
                BarIcon(R.drawable.dexnav_menu, item.label,
                    Modifier.focusRequester(routeFocus.getValue(item.route)).focusProperties { down = contentFocus },
                    onFocused = { lastRoute = item.route }) { open(item.route, thenContent = true) }
            }
            BarIcon(R.drawable.dexnav_power, stringResource(R.string.dex_bar_exit),
                Modifier.focusProperties { down = contentFocus }, onClick = onExit)
            Spacer(Modifier.width(12.dp))
            ClockIsland(Modifier.focusProperties { down = contentFocus })
        }
    }
}

/** Home, Movies, Series, Add-ons | Discover, My Library, then Arc's own pages, as skin.dexhub orders them. */
@Composable
private fun dexTabs(items: List<DrawerItem>): List<DexTabSpec> {
    val home = stringResource(R.string.dex_nav_home)
    val movies = stringResource(R.string.dex_nav_movies)
    val series = stringResource(R.string.dex_nav_series)
    val addons = stringResource(R.string.dex_nav_addons)
    val library = stringResource(R.string.dex_nav_library)
    return remember(items, home, movies, series, addons, library) {
        buildList {
            add(DexTabSpec(Screen.Home.route, home, R.drawable.dexnav_home, R.drawable.dexnav_home_on))
            add(DexTabSpec(DexHomeTypeFilter.MOVIES_ROUTE, movies, R.drawable.dexnav_movie, R.drawable.dexnav_movie_on))
            add(DexTabSpec(DexHomeTypeFilter.SERIES_ROUTE, series, R.drawable.dexnav_series, R.drawable.dexnav_series_on))
            add(DexTabSpec(Screen.AddonManager.route, addons, R.drawable.dexnav_addons, R.drawable.dexnav_addons_on))
            items.filter { it.route != Screen.Home.route && it.route !in TrailingRoutes }.forEach { item ->
                when (item.route) {
                    Screen.Discover.route -> add(DexTabSpec(item.route, item.label, R.drawable.dexnav_discover, R.drawable.dexnav_discover))
                    Screen.Library.route -> add(DexTabSpec(item.route, library, R.drawable.dexnav_library, R.drawable.dexnav_library_on))
                    Screen.Calendar.route -> add(DexTabSpec(item.route, item.label, R.drawable.dexnav_calendar, R.drawable.dexnav_calendar))
                    else -> add(DexTabSpec(item.route, item.label, R.drawable.dexnav_dexworld, R.drawable.dexnav_dexworld))
                }
            }
        }
    }
}

@Composable
@OptIn(ExperimentalTvMaterial3Api::class)
private fun ProfileButton(profile: AvatarModel?, modifier: Modifier, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    Button(onClick = onClick,
        modifier = modifier.height(26.dp).widthIn(min = 70.dp).onFocusChanged { focused = it.isFocused },
        shape = ButtonDefaults.shape(RoundedCornerShape(13.dp)),
        scale = ButtonDefaults.scale(focusedScale = 1f),
        contentPadding = PaddingValues(horizontal = 6.dp),
        colors = ButtonDefaults.colors(containerColor = Color.Transparent, focusedContainerColor = FocusFill,
            contentColor = Color.White, focusedContentColor = OnFocus)) {
        Row(Modifier.height(26.dp), verticalAlignment = Alignment.CenterVertically) {
            if (profile != null) {
                NuvioAvatar(profile, 20.dp, description = null)
                Text(profile.name, modifier = Modifier.padding(start = 7.dp).widthIn(max = 78.dp),
                    fontSize = 15.sp, fontWeight = FontWeight.Bold, maxLines = 1,
                    color = if (focused) OnFocus else Color.White)
            } else {
                Icon(painterResource(R.drawable.app_logo_wordmark), stringResource(R.string.app_name),
                    Modifier.width(61.dp).height(18.dp), tint = if (focused) OnFocus else Color.Unspecified)
            }
        }
    }
}

@Composable
@OptIn(ExperimentalTvMaterial3Api::class)
private fun DexTab(tab: DexTabSpec, isSelected: Boolean, modifier: Modifier, onFocused: () -> Unit, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val showName = focused || isSelected
    Button(onClick = onClick,
        modifier = modifier.height(26.dp).widthIn(min = 32.dp)
            .onFocusChanged { focused = it.isFocused; if (it.isFocused) onFocused() }
            .semantics { selected = isSelected; contentDescription = tab.label },
        shape = ButtonDefaults.shape(RoundedCornerShape(13.dp)),
        scale = ButtonDefaults.scale(focusedScale = 1f),
        contentPadding = PaddingValues(start = 9.dp, end = if (showName) 9.dp else 9.dp),
        colors = ButtonDefaults.colors(containerColor = if (isSelected) SelectedFill else Color.Transparent,
            focusedContainerColor = FocusFill, contentColor = Color.White, focusedContentColor = OnFocus)) {
        Row(Modifier.height(26.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(painterResource(if (isSelected) tab.iconOn else tab.icon), null, Modifier.size(14.dp),
                tint = when {
                    focused -> OnFocus
                    isSelected -> Color.White
                    else -> IdleIcon
                })
            if (showName) {
                Text(tab.label, modifier = Modifier.padding(start = 7.dp), fontSize = 14.sp, maxLines = 1,
                    color = if (focused) OnFocus else Color.White)
            }
        }
    }
}

@Composable
@OptIn(ExperimentalTvMaterial3Api::class)
private fun BarIcon(@DrawableRes icon: Int, label: String, modifier: Modifier, onFocused: () -> Unit = {}, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    Button(onClick = onClick,
        modifier = modifier.padding(horizontal = 2.dp).width(34.dp).height(26.dp)
            .onFocusChanged { focused = it.isFocused; if (it.isFocused) onFocused() }
            .semantics { contentDescription = label },
        shape = ButtonDefaults.shape(RoundedCornerShape(13.dp)),
        scale = ButtonDefaults.scale(focusedScale = 1f),
        contentPadding = PaddingValues(0.dp),
        colors = ButtonDefaults.colors(containerColor = Color.Transparent, focusedContainerColor = FocusFill,
            contentColor = IdleIcon, focusedContentColor = OnFocus)) {
        Box(Modifier.width(34.dp).height(26.dp), contentAlignment = Alignment.Center) {
            Icon(painterResource(icon), null, Modifier.size(14.dp), tint = if (focused) OnFocus else IdleIcon)
        }
    }
}

/** The island pill (button 9510) at rest: bell, a divider and the clock. */
@Composable
@OptIn(ExperimentalTvMaterial3Api::class)
private fun ClockIsland(modifier: Modifier) {
    val context = LocalContext.current
    val format = remember(context) { android.text.format.DateFormat.getTimeFormat(context) }
    var now by remember { mutableStateOf(format.format(Date())) }
    LaunchedEffect(format) {
        while (true) {
            now = format.format(Date())
            delay(15_000L)
        }
    }
    var focused by remember { mutableStateOf(false) }
    Button(onClick = {},
        modifier = modifier.width(122.dp).height(26.dp).onFocusChanged { focused = it.isFocused }
            .border(0.5.dp, if (focused) Color.Transparent else Color(0x24FFFFFF), RoundedCornerShape(13.dp)),
        shape = ButtonDefaults.shape(RoundedCornerShape(13.dp)),
        scale = ButtonDefaults.scale(focusedScale = 1f),
        contentPadding = PaddingValues(0.dp),
        colors = ButtonDefaults.colors(containerColor = Color(0xC70E1014), focusedContainerColor = FocusFill,
            contentColor = Color.White, focusedContentColor = OnFocus)) {
        Row(Modifier.width(122.dp).height(26.dp), verticalAlignment = Alignment.CenterVertically) {
            Spacer(Modifier.width(10.dp))
            Icon(painterResource(R.drawable.dexnav_bell), null, Modifier.size(14.dp),
                tint = if (focused) OnFocus else Color(0x8CFFFFFF))
            Spacer(Modifier.width(11.dp))
            Box(Modifier.width(1.dp).height(11.dp).background(if (focused) Color(0x33000000) else Color(0x33FFFFFF)))
            Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                Text(now, fontSize = 16.sp, fontWeight = FontWeight.Bold, maxLines = 1,
                    color = if (focused) OnFocus else Color.White)
            }
        }
    }
}
