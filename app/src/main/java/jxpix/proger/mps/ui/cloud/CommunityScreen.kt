package jxpix.proger.mps.ui.cloud

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import jxpix.proger.mps.R
import jxpix.proger.mps.cloud.Cloud
import jxpix.proger.mps.cloud.CloudItem
import jxpix.proger.mps.cloud.CloudKind
import jxpix.proger.mps.cloud.CloudSort
import jxpix.proger.mps.studio.Studio
import jxpix.proger.mps.ui.Fmt
import jxpix.proger.mps.ui.Ic
import jxpix.proger.mps.ui.components.AppSheet
import jxpix.proger.mps.ui.components.Chip
import jxpix.proger.mps.ui.components.IconBtn
import jxpix.proger.mps.ui.components.SheetItem
import jxpix.proger.mps.ui.theme.Mps
import java.util.Locale

internal fun kindIcon(kind: CloudKind?): ImageVector = when (kind) {
    CloudKind.SOUND -> Ic.Wave
    CloudKind.PRESET -> Ic.Tune
    CloudKind.PROJECT -> Ic.Folder
    CloudKind.TRACK -> Ic.Album
    null -> Ic.AudioFile
}

internal fun kindName(kind: CloudKind?): Int = when (kind) {
    CloudKind.SOUND -> R.string.cloud_kind_sound
    CloudKind.PRESET -> R.string.cloud_kind_preset
    CloudKind.PROJECT -> R.string.cloud_kind_project
    CloudKind.TRACK -> R.string.cloud_kind_track
    null -> R.string.cloud_all
}

internal fun sortName(sort: CloudSort): Int = when (sort) {
    CloudSort.NEW -> R.string.cloud_sort_new
    CloudSort.POPULAR -> R.string.cloud_sort_popular
    CloudSort.BEST -> R.string.cloud_sort_best
}

internal fun stars(value: Float): String = String.format(Locale.ROOT, "%.1f", value)

/** The community: a catalogue of what people have shared, over the whole screen. */
@Composable
fun CommunityScreen(studio: Studio) {
    val cloud = studio.cloud
    val c = Mps.colors
    var searching by rememberSaveable { mutableStateOf(false) }
    var sortMenu by remember { mutableStateOf(false) }
    var publish by remember { mutableStateOf(false) }

    BackHandler { cloud.hide() }

    Box(Modifier.fillMaxSize().background(c.bg).windowInsetsPadding(WindowInsets.safeDrawing)) {
        Column(Modifier.fillMaxSize()) {
            Row(Modifier.fillMaxWidth().height(52.dp).padding(end = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                IconBtn(Ic.Back, stringResource(R.string.back), { cloud.hide() })
                Text(
                    stringResource(R.string.community), Modifier.weight(1f), color = c.text, fontSize = 18.sp,
                    fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
                IconBtn(
                    Ic.Search, stringResource(R.string.cloud_search),
                    {
                        searching = !searching
                        if (!searching) cloud.searchFor("")
                    },
                    tint = if (searching || cloud.search.isNotEmpty()) c.accent else c.text,
                )
                IconBtn(
                    Ic.Sort, stringResource(R.string.cloud_sort) + ": " + stringResource(sortName(cloud.sort)), { sortMenu = true },
                    tint = if (cloud.sort != CloudSort.NEW) c.accent else c.text,
                )
                Spacer(Modifier.width(4.dp))
                val session = cloud.session
                if (!cloud.configured) {
                    // nothing to sign in to
                } else if (session == null) {
                    Chip(stringResource(R.string.cloud_sign_in), { cloud.askSignIn = true }, icon = Ic.Person)
                } else {
                    Chip(
                        cloud.me?.username ?: stringResource(R.string.cloud_profile), { cloud.showAuthor(session.userId) },
                        icon = Ic.Person, maxWidth = 150.dp,
                    )
                }
            }
            if (searching) SearchField(cloud.search) { cloud.searchFor(it) }
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically,
            ) {
                Chip(stringResource(R.string.cloud_all), { cloud.filter(null) }, selected = cloud.kind == null && !cloud.hiddenOnly)
                Chip(stringResource(R.string.cloud_sounds), { cloud.filter(CloudKind.SOUND) }, selected = cloud.kind == CloudKind.SOUND && !cloud.hiddenOnly)
                Chip(stringResource(R.string.cloud_presets), { cloud.filter(CloudKind.PRESET) }, selected = cloud.kind == CloudKind.PRESET && !cloud.hiddenOnly)
                Chip(stringResource(R.string.cloud_projects), { cloud.filter(CloudKind.PROJECT) }, selected = cloud.kind == CloudKind.PROJECT && !cloud.hiddenOnly)
                Chip(stringResource(R.string.cloud_tracks), { cloud.filter(CloudKind.TRACK) }, selected = cloud.kind == CloudKind.TRACK && !cloud.hiddenOnly)
                if (cloud.isModerator) {
                    Chip(stringResource(R.string.cloud_hidden_filter), { cloud.showHidden(!cloud.hiddenOnly) }, selected = cloud.hiddenOnly, icon = Ic.Flag)
                }
            }
            Box(Modifier.weight(1f).fillMaxWidth()) {
                val failure = cloud.failure
                when {
                    failure != null && cloud.items.isEmpty() ->
                        Notice(cloud.errorText(failure), if (cloud.configured) stringResource(R.string.cloud_retry) else null) { cloud.refresh() }
                    cloud.items.isEmpty() && cloud.loading ->
                        CircularProgressIndicator(Modifier.align(Alignment.Center), color = c.accent, trackColor = c.accentSoft)
                    cloud.items.isEmpty() ->
                        Notice(stringResource(if (cloud.search.isEmpty()) R.string.cloud_empty else R.string.cloud_nothing_found), null) {}
                    else -> ItemGrid(cloud, cloud.items, PaddingValues(start = 8.dp, end = 8.dp, top = 4.dp, bottom = 84.dp), footer = cloud.canLoadMore)
                }
            }
        }
        if (cloud.configured) {
            Row(
                Modifier
                    .align(Alignment.BottomEnd)
                    .padding(16.dp)
                    .height(48.dp)
                    .clip(RoundedCornerShape(24.dp))
                    .background(c.accent)
                    .clickable(role = Role.Button) { if (cloud.signedIn) publish = true else cloud.askSignIn = true }
                    .padding(start = 16.dp, end = 20.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Ic.Upload, null, Modifier.size(20.dp), tint = c.onAccent)
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.cloud_publish), color = c.onAccent, fontSize = 14.sp, fontWeight = FontWeight.Bold)
            }
        }
    }

    if (sortMenu) {
        AppSheet({ sortMenu = false }, title = stringResource(R.string.cloud_sort)) {
            for (sort in CloudSort.entries) {
                SheetItem(stringResource(sortName(sort)), {
                    sortMenu = false
                    cloud.sortBy(sort)
                }, selected = cloud.sort == sort)
            }
        }
    }
    if (cloud.authorId != null) AuthorSheet(studio)
    cloud.opened?.let { ItemSheet(studio, it) }
    if (publish) PublishSheet(studio) { publish = false }
    if (cloud.askSignIn) AuthSheet(studio)
}

@Composable
private fun SearchField(initial: String, onSearch: (String) -> Unit) {
    var text by remember { mutableStateOf(initial) }
    val focus = LocalFocusManager.current
    OutlinedTextField(
        value = text,
        onValueChange = { if (it.length <= 40) text = it },
        singleLine = true,
        label = { Text(stringResource(R.string.cloud_search_hint)) },
        leadingIcon = { Icon(Ic.Search, null, Modifier.size(20.dp)) },
        trailingIcon = {
            if (text.isNotEmpty()) IconBtn(Ic.Close, stringResource(R.string.cancel), {
                text = ""
                onSearch("")
            }, size = 40.dp, iconSize = 18.dp)
        },
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        keyboardActions = KeyboardActions(onSearch = {
            focus.clearFocus()
            onSearch(text.trim())
        }),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 2.dp),
    )
}

/** A text in the middle of the list area, with an optional button under it. */
@Composable
internal fun Notice(text: String, action: String?, onAction: () -> Unit) {
    val c = Mps.colors
    Column(
        Modifier.fillMaxSize().padding(horizontal = 32.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(text, color = c.textDim, fontSize = 14.sp, lineHeight = 20.sp, textAlign = TextAlign.Center, modifier = Modifier.widthIn(max = 420.dp))
        if (action != null) {
            Spacer(Modifier.height(12.dp))
            Chip(action, onAction, icon = Ic.Refresh)
        }
    }
}

@Composable
internal fun ItemGrid(cloud: Cloud, items: List<CloudItem>, padding: PaddingValues, footer: Boolean) {
    val c = Mps.colors
    LazyVerticalGrid(
        columns = GridCells.Adaptive(minSize = 330.dp), modifier = Modifier.fillMaxSize(), contentPadding = padding,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(items, key = { it.id }) { item -> ItemRow(cloud, item) }
        if (footer) {
            item(key = "more", span = { GridItemSpan(maxLineSpan) }) {
                // reaching the end of the list asks for the next page
                LaunchedEffect(items.size) { cloud.loadMore() }
                Box(Modifier.fillMaxWidth().height(56.dp), contentAlignment = Alignment.Center) {
                    if (cloud.loadingMore) CircularProgressIndicator(Modifier.size(24.dp), color = c.accent, trackColor = c.accentSoft, strokeWidth = 3.dp)
                    else Chip(stringResource(R.string.cloud_load_more), { cloud.loadMore() })
                }
            }
        }
    }
}

/** Round button that plays an item, shows that it is being fetched, or stops it. */
@Composable
internal fun PlayButton(cloud: Cloud, item: CloudItem, size: Int = 40) {
    val c = Mps.colors
    val playing = cloud.playingId == item.id
    val preparing = cloud.preparingId == item.id
    if (!cloud.canPlay(item)) {
        Box(Modifier.size(size.dp).clip(CircleShape).background(c.surfaceHigh), contentAlignment = Alignment.Center) {
            Icon(kindIcon(item.cloudKind), null, Modifier.size(20.dp), tint = c.textDim)
        }
        return
    }
    val label = stringResource(if (playing || preparing) R.string.cloud_stop_listening else R.string.cloud_listen, item.title)
    Box(
        Modifier
            .size(size.dp)
            .clip(CircleShape)
            .background(if (playing) c.accent else c.accentSoft)
            .clickable(role = Role.Button) { cloud.togglePlay(item) }
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        if (preparing) {
            CircularProgressIndicator(Modifier.size(20.dp), color = c.accent, trackColor = Color.Transparent, strokeWidth = 2.5.dp)
        } else {
            Icon(if (playing) Ic.Stop else Ic.Play, null, Modifier.size(22.dp), tint = if (playing) c.onAccent else c.accent)
        }
    }
}

@Composable
internal fun ItemRow(cloud: Cloud, item: CloudItem) {
    val c = Mps.colors
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 64.dp)
            .clip(RoundedCornerShape(14.dp))
            .clickable { cloud.open(item) }
            .padding(horizontal = 8.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        PlayButton(cloud, item)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                item.title, color = if (item.hidden) c.textDim else c.text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            val parts = ArrayList<String>()
            parts += item.author
            parts += stringResource(kindName(item.cloudKind))
            if (item.durationMs > 0) parts += Fmt.soundLength(item.durationMs / 1000f, LocalResources.current)
            Text(parts.joinToString(" · "), color = c.textDim, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Spacer(Modifier.width(8.dp))
        Column(horizontalAlignment = Alignment.End) {
            Score(item)
            Spacer(Modifier.height(3.dp))
            val downloads = stringResource(R.string.cloud_downloads_desc, item.downloads)
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.semantics(mergeDescendants = true) { contentDescription = downloads }) {
                Icon(Ic.Download, null, Modifier.size(14.dp), tint = c.textDim)
                Spacer(Modifier.width(3.dp))
                Text(item.downloads.toString(), color = c.textDim, fontSize = 12.sp)
            }
        }
    }
}

/** A star with the average mark, or a dim star when there are no marks yet. */
@Composable
internal fun Score(item: CloudItem) {
    val c = Mps.colors
    val label = if (item.ratingCount > 0) stringResource(R.string.cloud_rating_desc, stars(item.rating), item.ratingCount)
    else stringResource(R.string.cloud_no_ratings)
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.semantics(mergeDescendants = true) { contentDescription = label }) {
        if (item.hidden) {
            Icon(Ic.Flag, null, Modifier.size(14.dp), tint = c.record)
            Spacer(Modifier.width(6.dp))
        }
        Icon(if (item.ratingCount > 0) Ic.StarFilled else Ic.Star, null, Modifier.size(14.dp), tint = if (item.ratingCount > 0) c.accent else c.textDim)
        Spacer(Modifier.width(3.dp))
        Text(
            if (item.ratingCount > 0) stars(item.rating) else "—", color = if (item.ratingCount > 0) c.text else c.textDim, fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold,
        )
    }
}
