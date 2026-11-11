package com.taktey.simplepdfreader

import android.graphics.Bitmap
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.NavigateBefore
import androidx.compose.material.icons.automirrored.outlined.NavigateNext
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReaderApp(state: ReaderState, onOpen: () -> Unit, onClose: () -> Unit,
              onPosition: (Int, Int) -> Unit, onShare: (PdfSource) -> Unit) {
    BackHandler(enabled = state != ReaderState.Empty, onBack = onClose)
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(if (state is ReaderState.Ready) state.session.file.name else stringResource(R.string.app_name),
                            maxLines = 1, overflow = TextOverflow.Ellipsis,
                            style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                        if (state is ReaderState.Ready) {
                            Text(stringResource(R.string.pages_total, state.session.pageCount),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                },
                navigationIcon = {
                    if (state != ReaderState.Empty) ActionIcon(Icons.AutoMirrored.Outlined.ArrowBack, R.string.close, onClose)
                },
                actions = {
                    if (state is ReaderState.Ready) {
                        ActionIcon(Icons.Outlined.FolderOpen, R.string.open_another, onOpen)
                        ActionIcon(Icons.Outlined.IosShare, R.string.share, { onShare(state.session) })
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
    ) { insets ->
        Box(Modifier.fillMaxSize().padding(insets)) {
            when (state) {
                ReaderState.Empty -> Welcome(onOpen)
                ReaderState.Loading -> Column(Modifier.align(Alignment.Center).padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(20.dp)) {
                    CircularProgressIndicator()
                    Text(stringResource(R.string.loading))
                    TextButton(onClick = onClose) { Text(stringResource(R.string.cancel)) }
                }
                is ReaderState.Error -> Column(Modifier.align(Alignment.Center).padding(32.dp).widthIn(max = 420.dp),
                    horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    Icon(Icons.Outlined.Description, null, Modifier.size(56.dp), tint = MaterialTheme.colorScheme.error)
                    Text(stringResource(R.string.error_title), style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center)
                    Text(stringResource(state.message), textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Button(onClick = onOpen) { Text(stringResource(R.string.open_another)) }
                }
                is ReaderState.Ready -> key(state.session) { DocumentReader(state, onPosition) }
            }
        }
    }
}

@Composable
private fun Welcome(onOpen: () -> Unit) {
    Column(Modifier.fillMaxSize().padding(horizontal = 28.dp, vertical = 16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
      Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()),
          horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Column(Modifier.widthIn(max = 400.dp).padding(vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(24.dp)) {
            Surface(shape = RoundedCornerShape(24.dp), color = MaterialTheme.colorScheme.primaryContainer) {
                Icon(Icons.Outlined.Description, null, Modifier.padding(24.dp).size(48.dp),
                    tint = MaterialTheme.colorScheme.onPrimaryContainer)
            }
            Text(stringResource(R.string.welcome_title), fontSize = 36.sp, lineHeight = 42.sp,
                fontWeight = FontWeight.SemiBold, letterSpacing = (-1).sp)
            Text(stringResource(R.string.welcome_body), style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            Button(onClick = onOpen, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp), shape = RoundedCornerShape(16.dp)) {
                Icon(Icons.Outlined.FolderOpen, null)
                Spacer(Modifier.width(12.dp))
                Text(stringResource(R.string.open_pdf))
            }
        }
      }
        Row(Modifier.padding(top = 20.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(Icons.Outlined.Lock, null, Modifier.size(14.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(stringResource(R.string.local_note), style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun DocumentReader(ready: ReaderState.Ready, onPosition: (Int, Int) -> Unit) {
    val session = ready.session
    val list = rememberLazyListState(ready.initialPage, ready.initialOffset)
    val horizontal = rememberScrollState()
    val scope = rememberCoroutineScope()
    var zoom by rememberSaveable { mutableFloatStateOf(1f) }
    var showPageInput by rememberSaveable { mutableStateOf(false) }
    var pageNumber by rememberSaveable { mutableStateOf("") }
    BackHandler(enabled = showPageInput) { showPageInput = false }
    val currentPage by remember {
        derivedStateOf {
            val layout = list.layoutInfo
            val mostVisible = layout.visibleItemsInfo.maxByOrNull { item ->
                (minOf(item.offset + item.size, layout.viewportEndOffset) -
                    maxOf(item.offset, layout.viewportStartOffset)).coerceAtLeast(0)
            }
            (mostVisible?.index ?: ready.initialPage) + 1
        }
    }
    LaunchedEffect(list) {
        snapshotFlow { list.firstVisibleItemIndex to list.firstVisibleItemScrollOffset }
            .collect { (page, offset) -> onPosition(page, offset) }
    }
    val transform = rememberTransformableState { _, zoomChange, pan, _ ->
        zoom = (zoom * zoomChange).coerceIn(1f, 3f)
        if (zoom > 1f) {
            horizontal.dispatchRawDelta(-pan.x)
            list.dispatchRawDelta(-pan.y)
        }
    }
    Column(Modifier.fillMaxSize().imePadding()) {
        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth().clipToBounds()
            .background(MaterialTheme.colorScheme.surfaceVariant)) {
            val pageWidth = maxWidth * zoom
            Box(Modifier.fillMaxSize().transformable(transform, canPan = { zoom > 1f })
                .horizontalScroll(horizontal)) {
                LazyColumn(state = list, modifier = Modifier.width(pageWidth).fillMaxHeight(),
                    contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    items(session.pageCount, key = { it }) { index ->
                        PageSurface(session, index)
                    }
                }
            }
        }
        Surface(tonalElevation = 2.dp) {
            Column(Modifier.fillMaxWidth()) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center) {
                    ActionIcon(Icons.AutoMirrored.Outlined.NavigateBefore, R.string.previous_page,
                        { scope.launch { list.scrollToItem((currentPage - 2).coerceAtLeast(0)) } }, currentPage > 1)
                    TextButton(onClick = { showPageInput = !showPageInput }, modifier = Modifier.weight(1f)) {
                        Text(stringResource(R.string.page_count, currentPage, session.pageCount))
                    }
                    ActionIcon(Icons.AutoMirrored.Outlined.NavigateNext, R.string.next_page,
                        { scope.launch { list.scrollToItem(currentPage.coerceAtMost(session.pageCount - 1)) } }, currentPage < session.pageCount)
                }
                if (showPageInput) {
                    val target = pageNumber.toIntOrNull()?.takeIf { it in 1..session.pageCount }
                    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        OutlinedTextField(value = pageNumber,
                            onValueChange = { pageNumber = it.filter(Char::isDigit).take(7) },
                            modifier = Modifier.weight(1f),
                            label = { Text(stringResource(R.string.page_number, session.pageCount)) }, singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                        TextButton(enabled = target != null, onClick = {
                            target?.let { scope.launch { list.scrollToItem(it - 1) } }
                            showPageInput = false
                        }) { Text(stringResource(R.string.go)) }
                        ActionIcon(Icons.Outlined.Close, R.string.cancel, { showPageInput = false })
                    }
                }
                Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center) {
                    ActionIcon(Icons.Outlined.Remove, R.string.zoom_out, { zoom = (zoom - .25f).coerceAtLeast(1f) }, zoom > 1f)
                    Text(stringResource(R.string.zoom_percent, (zoom * 100).roundToInt()), Modifier.width(56.dp), textAlign = TextAlign.Center,
                        style = MaterialTheme.typography.labelLarge)
                    ActionIcon(Icons.Outlined.Add, R.string.zoom_in, { zoom = (zoom + .25f).coerceAtMost(3f) }, zoom < 3f)
                    Spacer(Modifier.weight(1f))
                    TextButton(onClick = { zoom = 1f; scope.launch { horizontal.scrollTo(0) } }) {
                        Text(stringResource(R.string.reset_zoom))
                    }
                }
            }
        }
    }
}

@Composable
private fun PageSurface(session: PdfSource, index: Int) {
    var retry by remember { mutableIntStateOf(0) }
    var aspect by remember { mutableFloatStateOf(.707f) }
    var bitmap by remember { mutableStateOf<Bitmap?>(null) }
    var failed by remember { mutableStateOf(false) }
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val density = LocalDensity.current
        val renderWidth = with(density) { maxWidth.roundToPx() }
        LaunchedEffect(session, index, renderWidth, retry) {
            failed = false
            try {
                aspect = session.pageSize(index).ratio.coerceIn(.08f, 12f)
                // Keep the previous bitmap visible while a pinch gesture changes the layout.
                if (bitmap != null) delay(180)
                bitmap = session.render(index, renderWidth)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { failed = true }
        }
        Surface(Modifier.fillMaxWidth().aspectRatio(aspect), color = Color.White,
            shadowElevation = 2.dp, shape = RoundedCornerShape(2.dp)) {
            Box(contentAlignment = Alignment.Center) {
                val page = bitmap
                if (page != null) Image(page.asImageBitmap(), stringResource(R.string.page_label, index + 1), Modifier.fillMaxSize())
                if (failed) {
                    Column(Modifier.padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(stringResource(R.string.error_page), color = Color.DarkGray, textAlign = TextAlign.Center)
                        TextButton(onClick = { retry++ }) { Text(stringResource(R.string.retry)) }
                    }
                } else if (page == null) CircularProgressIndicator(Modifier.size(28.dp))
            }
        }
    }
}

@Composable
private fun ActionIcon(icon: ImageVector, description: Int, onClick: () -> Unit, enabled: Boolean = true) {
    IconButton(onClick = onClick, enabled = enabled) { Icon(icon, contentDescription = stringResource(description)) }
}
