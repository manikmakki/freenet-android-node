package org.freenet.androidnode

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.lightColorScheme
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.DialogProperties
import java.io.ByteArrayInputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

class MainActivity : ComponentActivity() {
    private val nodeViewModel: NodeViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(
                colorScheme = if (isSystemInDarkTheme()) {
                    darkColorScheme()
                } else {
                    lightColorScheme()
                },
            ) {
                Surface(modifier = Modifier.fillMaxSize()) {
                    var disclaimerAccepted by remember {
                        mutableStateOf(AlphaDisclaimerAcceptance.isAccepted(this@MainActivity))
                    }
                    if (disclaimerAccepted) {
                        NodeScreen(nodeViewModel)
                    } else {
                        AlphaDisclaimerDialog(
                            onAccept = {
                                AlphaDisclaimerAcceptance.accept(this@MainActivity)
                                disclaimerAccepted = true
                            },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun AlphaDisclaimerDialog(onAccept: () -> Unit) {
    var riskAccepted by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = {},
        properties = DialogProperties(
            dismissOnBackPress = false,
            dismissOnClickOutside = false,
        ),
        title = { Text("Warning: This application runs a full Freenet node.") },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    "This is an unofficial, community-built app. It is not published, " +
                        "maintained, or endorsed by the Freenet Project.",
                    fontWeight = FontWeight.Bold,
                )
                Text("Freenet is not yet optimized for mobile devices.")
                Text("Running a node may result in:")
                Text(
                    "Significant battery drain\n" +
                        "High CPU usage and device heating\n" +
                        "Large Wi-Fi data usage\n" +
                        "Reduced device performance",
                )
                Text("This software is intended for developers and early adopters only.")
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .toggleable(
                            value = riskAccepted,
                            role = Role.Checkbox,
                            onValueChange = { riskAccepted = it },
                        )
                        .padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Checkbox(
                        checked = riskAccepted,
                        onCheckedChange = null,
                    )
                    Text(
                        "I have read and accept the risks and notices in this disclaimer",
                        modifier = Modifier.padding(start = 8.dp),
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = onAccept,
                enabled = riskAccepted,
            ) {
                Text("Accept and continue")
            }
        },
    )
}

@Composable
private fun NodeScreen(nodeViewModel: NodeViewModel) {
    val context = LocalContext.current
    val nodeState by nodeViewModel.state.collectAsState()
    val policyState by nodeViewModel.policies.collectAsState()
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    var pendingNotificationAction by remember { mutableStateOf<(() -> Unit)?>(null) }
    var showDiagnostics by remember { mutableStateOf(false) }
    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) {
            pendingNotificationAction?.invoke()
        } else {
            nodeViewModel.reportNotificationPermissionRequired()
        }
        pendingNotificationAction = null
    }

    fun withNotificationPermission(action: () -> Unit) {
        if (
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            pendingNotificationAction = action
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            action()
        }
    }

    fun closeDrawer() {
        scope.launch { drawerState.close() }
    }

    BackHandler(enabled = drawerState.isOpen || showDiagnostics) {
        if (drawerState.isOpen) {
            closeDrawer()
        } else {
            showDiagnostics = false
        }
    }

    ModalNavigationDrawer(
        drawerState = drawerState,
        gesturesEnabled = false,
        drawerContent = {
            ModalDrawerSheet {
                Column(
                    modifier = Modifier
                        .windowInsetsPadding(
                            WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal),
                        )
                        .padding(16.dp)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Image(
                            painter = painterResource(R.drawable.ic_freenet_logo),
                            contentDescription = null,
                            modifier = Modifier.height(32.dp),
                        )
                        Column {
                            Text("Freenet Android Node", style = MaterialTheme.typography.titleLarge)
                            Text(
                                "Unofficial · not affiliated with the Freenet Project",
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.Bold,
                            )
                        }
                    }
                    OutlinedButton(
                        onClick = {
                            scope.launch {
                                drawerState.close()
                                showDiagnostics = false
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("Dashboard")
                    }
                    NodeControlStrip(
                        state = nodeState,
                        onStartLocal = {
                            withNotificationPermission(nodeViewModel::startLocalNode)
                            closeDrawer()
                        },
                        onStartNetwork = {
                            withNotificationPermission(nodeViewModel::startNetworkNode)
                            closeDrawer()
                        },
                        onPause = {
                            nodeViewModel.pauseNode()
                            closeDrawer()
                        },
                        onStop = {
                            nodeViewModel.stopNode()
                            closeDrawer()
                        },
                    )
                    HorizontalDivider()
                    PolicyControls(
                        policies = policyState,
                        onPowerPolicy = { policy ->
                            if (policy == NodePowerPolicy.Manual) {
                                nodeViewModel.setPowerPolicy(policy)
                            } else {
                                withNotificationPermission {
                                    nodeViewModel.setPowerPolicy(policy)
                                }
                            }
                        },
                        onNetworkDataPolicy = nodeViewModel::setNetworkDataPolicy,
                    )
                    HorizontalDivider()
                    OutlinedButton(
                        onClick = {
                            scope.launch {
                                drawerState.close()
                                showDiagnostics = true
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("For nerds")
                    }
                }
            }
        },
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.safeDrawing),
        ) {
            if (showDiagnostics) {
                DiagnosticsPanel(modifier = Modifier.fillMaxSize())
            } else {
                DashboardPanel(
                    state = nodeState,
                    modifier = Modifier.fillMaxSize(),
                )
            }
            if (drawerState.isClosed) {
                Surface(
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(8.dp),
                    shape = MaterialTheme.shapes.medium,
                    tonalElevation = 6.dp,
                    shadowElevation = 4.dp,
                ) {
                    IconButton(onClick = { scope.launch { drawerState.open() } }) {
                        Text("☰", style = MaterialTheme.typography.titleLarge)
                    }
                }
            }
        }
    }
}

@Composable
private fun NodeControlStrip(
    state: NodeUiState,
    onStartLocal: () -> Unit,
    onStartNetwork: () -> Unit,
    onPause: () -> Unit,
    onStop: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text("Node state: ${state.state} · ${state.mode} · ${state.peers} peers")
        if (state.lastNetworkError != null) {
            Text(
                text = state.lastNetworkError,
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (state.serviceActive) {
                OutlinedButton(
                    enabled = NativeBridge.isLoaded,
                    onClick = if (state.state == "Paused") onStartNetwork else onPause,
                    modifier = Modifier.weight(1f),
                ) {
                    Text(if (state.state == "Paused") "Resume node" else "Pause node")
                }
                Button(
                    enabled = NativeBridge.isLoaded,
                    onClick = onStop,
                    modifier = Modifier.weight(1f),
                ) {
                    Text("Stop node")
                }
            } else {
                Button(
                    enabled = NativeBridge.isLoaded,
                    onClick = onStartNetwork,
                    modifier = Modifier.weight(1f),
                ) {
                    Text("Start network node")
                }
                OutlinedButton(
                    enabled = NativeBridge.isLoaded,
                    onClick = onStartLocal,
                    modifier = Modifier.weight(1f),
                ) {
                    Text("Start local node")
                }
            }
        }
    }
}

@Composable
private fun PolicyControls(
    policies: NodePolicyState,
    onPowerPolicy: (NodePowerPolicy) -> Unit,
    onNetworkDataPolicy: (NetworkDataPolicy) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Node runs when", style = MaterialTheme.typography.titleMedium)
        NodePowerPolicy.entries.forEach { policy ->
            FilterChip(
                selected = policies.power == policy,
                onClick = { onPowerPolicy(policy) },
                label = { Text(policy.displayName) },
                modifier = Modifier.fillMaxWidth(),
            )
        }
        Text(
            "Charging and Always keep a lightweight foreground controller active while the node waits.",
            style = MaterialTheme.typography.bodySmall,
        )
        Text("Network data", style = MaterialTheme.typography.titleMedium)
        NetworkDataPolicy.entries.forEach { policy ->
            FilterChip(
                selected = policies.networkData == policy,
                onClick = { onNetworkDataPolicy(policy) },
                label = { Text(policy.displayName) },
                modifier = Modifier.fillMaxWidth(),
            )
        }
        Text(
            "Uses Android's validated and metered network status, regardless of Wi-Fi, cellular, Ethernet, or VPN.",
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

@Composable
private fun DashboardPanel(state: NodeUiState, modifier: Modifier = Modifier) {
    val running = state.state == "RunningLocal" || state.state == "RunningNetwork"
    if (running) {
        CoreDashboardWebView(
            reloadKey = "${state.mode}:${state.completedStartCycles}",
            modifier = modifier,
        )
    } else {
        Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(
                modifier = Modifier.padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (state.state == "Starting" || state.state == "Stopping") {
                    CircularProgressIndicator()
                }
                Text(state.detail)
                Text(
                    "The core dashboard becomes available at 127.0.0.1:7509 while the node runs.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}

@Composable
private fun CoreDashboardWebView(reloadKey: String, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    var loading by remember(reloadKey) { mutableStateOf(true) }
    var error by remember(reloadKey) { mutableStateOf<String?>(null) }
    val webView = remember(reloadKey) {
        WebView(context).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.allowFileAccess = false
            settings.allowContentAccess = false
            settings.javaScriptCanOpenWindowsAutomatically = false
            settings.setSupportMultipleWindows(false)
            settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                settings.safeBrowsingEnabled = true
            }
            webViewClient = LoopbackDashboardClient(
                onLoading = {
                    loading = true
                    error = null
                },
                onReady = {
                    loading = false
                    error = null
                },
                onError = {
                    loading = false
                    error = it
                },
            )
            loadUrl(DASHBOARD_URL)
        }
    }

    DisposableEffect(webView) {
        onDispose {
            webView.stopLoading()
            webView.webViewClient = WebViewClient()
            webView.loadUrl("about:blank")
            webView.destroy()
        }
    }

    Box(modifier = modifier.fillMaxSize()) {
        AndroidView(
            factory = { webView },
            modifier = Modifier.fillMaxSize(),
        )
        if (loading) {
            CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
        }
        error?.let { message ->
            Column(
                modifier = Modifier
                    .align(Alignment.Center)
                    .padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text("Dashboard is not ready yet")
                Text(message, style = MaterialTheme.typography.bodySmall)
                Button(
                    onClick = {
                        loading = true
                        error = null
                        webView.reload()
                    },
                ) {
                    Text("Retry")
                }
            }
        }
    }
}

private class LoopbackDashboardClient(
    private val onLoading: () -> Unit,
    private val onReady: () -> Unit,
    private val onError: (String) -> Unit,
) : WebViewClient() {
    override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
        if (url?.let(::isAllowedDashboardUri) == true) {
            onLoading()
        }
    }

    override fun onPageFinished(view: WebView?, url: String?) {
        if (url?.let(::isAllowedDashboardUri) == true) {
            onReady()
        }
    }

    override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest): Boolean {
        if (shouldOpenInExternalBrowser(request.url, request.isForMainFrame, request.hasGesture())) {
            val result = runCatching {
                view?.context?.startActivity(Intent(Intent.ACTION_VIEW, request.url))
                    ?: error("WebView context is unavailable")
            }
            if (result.isFailure) {
                onError("No browser is available to open this link")
            }
            return true
        }
        return !isAllowedDashboardUri(request.url)
    }

    override fun shouldInterceptRequest(
        view: WebView?,
        request: WebResourceRequest,
    ): WebResourceResponse? {
        val scheme = request.url.scheme?.lowercase()
        if (scheme == "http" && !isAllowedDashboardUri(request.url)) {
            return blockedResponse()
        }
        if (scheme == "https" && !isAllowedDashboardSubresource(request.url)) {
            return blockedResponse()
        }
        return null
    }

    override fun onReceivedError(
        view: WebView?,
        request: WebResourceRequest,
        error: WebResourceError,
    ) {
        if (request.isForMainFrame) {
            onError(error.description?.toString() ?: "WebView could not load the dashboard")
        }
    }

    private fun blockedResponse(): WebResourceResponse = WebResourceResponse(
        "text/plain",
        "UTF-8",
        403,
        "Blocked non-loopback request",
        emptyMap(),
        ByteArrayInputStream("Blocked by the Android dashboard allowlist".toByteArray()),
    )
}

@Composable
private fun DiagnosticsPanel(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    var snapshot by remember { mutableStateOf("Collecting diagnostics…") }

    LaunchedEffect(Unit) {
        while (true) {
            snapshot = withContext(Dispatchers.Default) { diagnosticSnapshot() }
            delay(2_000)
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { copyToClipboard(context, snapshot) }) {
                Text("Copy JSON")
            }
            Text(
                "Metrics and the bounded, sanitized native log ring.",
                modifier = Modifier.align(Alignment.CenterVertically),
                style = MaterialTheme.typography.bodySmall,
            )
        }
        SelectionContainer(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .verticalScroll(rememberScrollState()),
        ) {
            Text(
                text = snapshot,
                fontFamily = FontFamily.Monospace,
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

private fun diagnosticSnapshot(): String {
    fun resultOrError(result: Result<String>): Any = result.fold(
        onSuccess = { value ->
            runCatching { JSONObject(value) }.fold(
                onSuccess = { it },
                onFailure = { value },
            )
        },
        onFailure = { error -> JSONObject().put("error", error.message ?: "unknown JNI error") },
    )

    return JSONObject()
        .put("capturedAtEpochMs", System.currentTimeMillis())
        .put("nodeStatus", resultOrError(NativeBridge.nodeStatus()))
        .put("recentLogs", resultOrError(NativeBridge.recentLogs(DIAGNOSTIC_LOG_ENTRIES)))
        .put("androidAdapter", resultOrError(NativeBridge.buildInfo()))
        .put("freenetCore", resultOrError(NativeBridge.freenetBuildInfo()))
        .toString(2)
}

private fun copyToClipboard(context: Context, value: String) {
    val clipboard = context.getSystemService(ClipboardManager::class.java)
    clipboard.setPrimaryClip(ClipData.newPlainText("Freenet diagnostics", value))
}

private fun isAllowedDashboardUri(value: String): Boolean =
    runCatching { isAllowedDashboardUri(Uri.parse(value)) }.getOrDefault(false)

private fun isAllowedDashboardUri(uri: Uri): Boolean =
    uri.scheme.equals("http", ignoreCase = true) &&
        uri.host == DASHBOARD_HOST &&
        uri.port == DASHBOARD_PORT

private fun isAllowedDashboardSubresource(uri: Uri): Boolean =
    uri.scheme.equals("https", ignoreCase = true) &&
        uri.host == DASHBOARD_LOGO_HOST &&
        uri.path == DASHBOARD_LOGO_PATH &&
        (uri.port == -1 || uri.port == 443)

internal fun shouldOpenInExternalBrowser(
    uri: Uri,
    isForMainFrame: Boolean,
    hasGesture: Boolean,
): Boolean {
    if (!isForMainFrame || !hasGesture) return false
    val scheme = uri.scheme?.lowercase()
    if (scheme != "http" && scheme != "https") return false
    return !isAllowedDashboardUri(uri) || uri.path?.startsWith(HOSTED_APP_PATH_PREFIX) == true
}

private const val DASHBOARD_HOST = "127.0.0.1"
private const val DASHBOARD_PORT = 7509
private const val DASHBOARD_URL = "http://$DASHBOARD_HOST:$DASHBOARD_PORT/"
private const val DASHBOARD_LOGO_HOST = "freenet.org"
private const val DASHBOARD_LOGO_PATH = "/freenet_logo.svg"
private const val HOSTED_APP_PATH_PREFIX = "/v1/contract/web/"
private const val DIAGNOSTIC_LOG_ENTRIES = 128
