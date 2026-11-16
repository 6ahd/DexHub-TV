package com.nuvio.tv.ui.v2.navigation

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.Explore
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Menu
import androidx.compose.material.icons.outlined.Movie
import androidx.compose.material.icons.outlined.Tv
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.VideoLibrary
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
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
import com.nuvio.tv.ui.screens.home.DexHomeTypeFilter
import com.nuvio.tv.ui.navigation.Screen
import com.nuvio.tv.ui.theme.NuvioTheme
import com.nuvio.tv.ui.v2.components.AvatarModel
import kotlinx.coroutines.delay
import java.util.Date

val LocalV2TopChrome = staticCompositionLocalOf { false }

/** Routes Dex Hub draws as icons at the right end of the bar instead of as tabs. */
private val TrailingRoutes = listOf(Screen.Search.route, Screen.Settings.route)

/**
 * Dex Hub's top bar (resources/skins/.../dexhub_browse.xml, controls 89-92):
 * the Dex mark on the left, icon tabs where the chosen or focused tab opens
 * into its name over an accent underline, then search, the menu and the clock
 * on the right. It sits straight on the page, over a soft dark fade.
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
    val moviesLabel = stringResource(R.string.dex_nav_movies)
    val seriesLabel = stringResource(R.string.dex_nav_series)
    // Home, Movies, Series, then the rest, as on Dex Hub's Home (window.py NAV_KEYS).
    val tabs = buildList {
        items.filter { it.route !in TrailingRoutes }.forEach { item ->
            add(item)
            if (item.route == Screen.Home.route) {
                add(DrawerItem(DexHomeTypeFilter.MOVIES_ROUTE, moviesLabel, icon = Icons.Outlined.Movie))
                add(DrawerItem(DexHomeTypeFilter.SERIES_ROUTE, seriesLabel, icon = Icons.Outlined.Tv))
            }
        }
    }
    val trailing = TrailingRoutes.mapNotNull { route -> items.firstOrNull { it.route == route } }
    val typeFilter by DexHomeTypeFilter.type.collectAsState()
    val activeRoute = if (selectedRoute == Screen.Home.route) DexHomeTypeFilter.routeFor(typeFilter) ?: selectedRoute else selectedRoute
    val routes = (tabs + trailing).map { it.route }
    val routeFocus = remember(routes) { routes.associateWith { FocusRequester() } }
    val logoFocus = remember { FocusRequester() }
    var lastRoute by remember { mutableStateOf(activeRoute) }
    var navFocused by remember { mutableStateOf(false) }
    var returnToContent by remember { mutableStateOf(false) }
    val entryFocus = routeFocus[lastRoute] ?: routeFocus[activeRoute] ?: routeFocus.values.firstOrNull() ?: logoFocus

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
    val go: (String) -> Unit = { route ->
        val type = DexHomeTypeFilter.typeFor(route)
        DexHomeTypeFilter.set(type)
        onNavigate(if (type != null) Screen.Home.route else route)
        returnToContent = true
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
        if (visible) {
            Box(Modifier.align(Alignment.TopCenter).fillMaxWidth().height(72.dp)
                .background(Brush.verticalGradient(listOf(Color(0xCC05070F), Color(0x6605070F), Color.Transparent))))
            Row(Modifier.align(Alignment.TopCenter).fillMaxWidth()
                .padding(start = 40.dp, end = 44.dp, top = 6.dp)
                .onFocusChanged { navFocused = it.hasFocus }.focusGroup(),
                verticalAlignment = Alignment.CenterVertically) {
                // The Dex mark is Nuvio's profile button, as on Dex Hub's Home.
                DexMark(Modifier.focusRequester(logoFocus).focusProperties { down = contentFocus },
                    onClick = { if (profile != null) onSwitchProfile() else go(Screen.Home.route) })
                Spacer(Modifier.width(48.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    tabs.forEach { item ->
                        DexTab(item = item, isSelected = item.route == activeRoute,
                            modifier = Modifier.focusRequester(routeFocus.getValue(item.route))
                                .focusProperties { down = contentFocus },
                            onFocused = { lastRoute = item.route },
                            onClick = { go(item.route) })
                    }
                }
                Spacer(Modifier.weight(1f))
                trailing.forEach { item ->
                    DexIconButton(icon = if (item.route == Screen.Search.route) Icons.Outlined.Search else Icons.Outlined.Menu,
                        label = item.label, isSelected = item.route == activeRoute,
                        modifier = Modifier.focusRequester(routeFocus.getValue(item.route))
                            .focusProperties { down = contentFocus },
                        onFocused = { lastRoute = item.route },
                        onClick = { go(item.route) })
                    Spacer(Modifier.width(10.dp))
                }
                Spacer(Modifier.width(26.dp))
                DexClock()
            }
        }
    }
}

/** Dex Hub's own glyph for each destination, falling back to the item's icon. */
private fun DrawerItem.dexIcon(): ImageVector? = when (route) {
    Screen.Home.route -> Icons.Outlined.Home
    Screen.Discover.route -> Icons.Outlined.Explore
    Screen.Library.route -> Icons.Outlined.VideoLibrary
    Screen.Calendar.route -> Icons.Outlined.CalendarMonth
    else -> icon
}

@Composable
@OptIn(ExperimentalTvMaterial3Api::class)
private fun DexMark(modifier: Modifier, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    Button(onClick = onClick,
        modifier = modifier.onFocusChanged { focused = it.isFocused },
        shape = ButtonDefaults.shape(RoundedCornerShape(10.dp)),
        scale = ButtonDefaults.scale(focusedScale = 1.04f),
        contentPadding = PaddingValues(horizontal = 6.dp, vertical = 4.dp),
        colors = ButtonDefaults.colors(containerColor = Color.Transparent,
            focusedContainerColor = Color.White.copy(alpha = .10f),
            contentColor = Color.White, focusedContentColor = Color.White)) {
        com.nuvio.tv.ui.v2.components.V2Wordmark(modifier = Modifier.width(92.dp).height(22.dp))
    }
}

@Composable
@OptIn(ExperimentalTvMaterial3Api::class)
private fun DexTab(item: DrawerItem, isSelected: Boolean, modifier: Modifier, onFocused: () -> Unit, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val open = isSelected || focused
    val accent = NuvioTheme.colors.Secondary
    val tint = when {
        focused -> Color.White
        isSelected -> Color.White
        else -> Color.White.copy(alpha = .62f)
    }
    Button(onClick = onClick,
        modifier = modifier
            .onFocusChanged { focused = it.isFocused; if (it.isFocused) onFocused() }
            .semantics { selected = isSelected; contentDescription = item.label },
        shape = ButtonDefaults.shape(RoundedCornerShape(8.dp)),
        scale = ButtonDefaults.scale(focusedScale = 1f),
        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp),
        colors = ButtonDefaults.colors(containerColor = Color.Transparent,
            focusedContainerColor = Color.White.copy(alpha = .08f),
            contentColor = tint, focusedContentColor = Color.White)) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                val vector = item.dexIcon()
                when {
                    vector != null -> Icon(vector, null, Modifier.size(15.dp), tint = if (open) accent else tint)
                    item.iconRes != null -> Icon(com.nuvio.tv.ui.util.rememberRawSvgPainter(item.iconRes, 15.dp), null,
                        Modifier.size(15.dp), tint = if (open) accent else tint)
                }
                AnimatedVisibility(visible = open, enter = fadeIn() + expandHorizontally(),
                    exit = fadeOut() + shrinkHorizontally()) {
                    Text(item.label, modifier = Modifier.padding(start = 8.dp), color = Color.White,
                        fontSize = 15.sp, fontWeight = FontWeight.Medium, maxLines = 1)
                }
            }
            Spacer(Modifier.height(4.dp))
            Box(Modifier.width(if (isSelected) 40.dp else 0.dp).height(2.dp)
                .background(if (isSelected) accent else Color.Transparent, RoundedCornerShape(1.dp)))
        }
    }
}

@Composable
@OptIn(ExperimentalTvMaterial3Api::class)
private fun DexIconButton(icon: ImageVector, label: String, isSelected: Boolean, modifier: Modifier,
                          onFocused: () -> Unit, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    Button(onClick = onClick,
        modifier = modifier
            .onFocusChanged { focused = it.isFocused; if (it.isFocused) onFocused() }
            .semantics { selected = isSelected; contentDescription = label },
        shape = ButtonDefaults.shape(RoundedCornerShape(50)),
        scale = ButtonDefaults.scale(focusedScale = 1.08f),
        contentPadding = PaddingValues(8.dp),
        colors = ButtonDefaults.colors(containerColor = Color.Transparent,
            focusedContainerColor = NuvioTheme.colors.Secondary,
            contentColor = if (isSelected) Color.White else Color.White.copy(alpha = .62f),
            focusedContentColor = Color.White)) {
        Icon(icon, null, Modifier.size(16.dp))
    }
}

@Composable
private fun DexClock() {
    val context = LocalContext.current
    val format = remember(context) { android.text.format.DateFormat.getTimeFormat(context) }
    var now by remember { mutableStateOf(format.format(Date())) }
    LaunchedEffect(format) {
        while (true) {
            now = format.format(Date())
            delay(15_000L)
        }
    }
    Text(now, color = Color.White.copy(alpha = .92f), fontSize = 19.sp, fontWeight = FontWeight.Medium)
}
