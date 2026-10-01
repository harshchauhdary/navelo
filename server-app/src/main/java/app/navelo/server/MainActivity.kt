package app.navelo.server

import android.Manifest
import android.app.UiModeManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.navelo.shared.*
import kotlinx.coroutines.launch

private val android.content.Context.uiStore by preferencesDataStore("phone_ui")
private val completedKey = booleanPreferencesKey("onboarded")
private val appearanceKey = stringPreferencesKey("appearance")
private val dynamicKey = booleanPreferencesKey("dynamic")
private const val appearanceBootstrapStore = "phone_ui_bootstrap"
private const val appearanceBootstrapKey = "appearance"

private fun Context.applyApplicationAppearance(appearance: String) {
    getSharedPreferences(appearanceBootstrapStore, Context.MODE_PRIVATE)
        .edit()
        .putString(appearanceBootstrapKey, appearance)
        .apply()
    if (Build.VERSION.SDK_INT >= 31) {
        val uiModeManager = getSystemService(UiModeManager::class.java)
        val mode = when (appearance) {
            "Light" -> UiModeManager.MODE_NIGHT_NO
            "Dark" -> UiModeManager.MODE_NIGHT_YES
            // For an application override, AUTO stores an undefined package
            // qualifier, so the app inherits the device's current night mode.
            else -> UiModeManager.MODE_NIGHT_AUTO
        }
        uiModeManager.setApplicationNightMode(mode)
    }
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        if (Build.VERSION.SDK_INT >= 31) {
            getSharedPreferences(appearanceBootstrapStore, Context.MODE_PRIVATE)
                .getString(appearanceBootstrapKey, null)
                ?.let { applyApplicationAppearance(it) }
        }
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { PhoneApp() }
    }
}

@Composable private fun PhoneApp() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val runtime = remember { ServerRuntime.get(context) }
    val state by runtime.state.collectAsStateWithLifecycle()
    val prefs by context.uiStore.data.collectAsStateWithLifecycle(initialValue = null)
    val appearance = prefs?.get(appearanceKey) ?: "System"
    val dark = appearance == "Dark" || (appearance == "System" && androidx.compose.foundation.isSystemInDarkTheme())
    val dynamic = prefs?.get(dynamicKey) ?: true
    LaunchedEffect(prefs) {
        if (prefs != null) context.applyApplicationAppearance(appearance)
    }
    val palette = when {
        dynamic && Build.VERSION.SDK_INT >= 31 -> if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        dark -> darkColorScheme(primary=Color(0xFF91E8CF), onPrimary=Color(0xFF07382F), background=Color(0xFF0D171E), surface=Color(0xFF15222B), secondary=Color(0xFFF0C68B))
        else -> lightColorScheme(primary=Color(0xFF166552), onPrimary=Color.White, background=Color(0xFFF7F9F6), surface=Color.White, secondary=Color(0xFF855F29))
    }
    MaterialTheme(colorScheme=palette) {
        Surface(Modifier.fillMaxSize()) {
            if (prefs == null) { Box(Modifier.fillMaxSize(), contentAlignment=Alignment.Center) { CircularProgressIndicator() }; return@Surface }
            var step by rememberSaveable { mutableIntStateOf(0) }
            var screen by rememberSaveable { mutableStateOf("home") }
            var picking by rememberSaveable { mutableStateOf(RootType.MOVIES) }
            var error by remember { mutableStateOf<String?>(null) }
            var removal by remember { mutableStateOf<MediaRoot?>(null) }
            val onboarded = prefs?.get(completedKey) == true
            val notify = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
            val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri: Uri? ->
                uri?.let { scope.launch {
                    runCatching {
                        context.contentResolver.takePersistableUriPermission(it, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        runtime.addRoot(it, picking)
                    }.onFailure { error = "We couldn't read this folder. Please choose it again." }
                } }
            }
            fun choose(type: RootType) { picking = type; picker.launch(null) }
            fun begin() {
                runtime.start()
                if (Build.VERSION.SDK_INT >= 33) notify.launch(Manifest.permission.POST_NOTIFICATIONS)
                scope.launch { context.uiStore.edit { it[completedKey] = true } }
            }
            BackHandler(enabled=screen != "home" || (!onboarded && step > 0)) {
                if (screen != "home") screen = "home" else step--
            }
            Column(Modifier.fillMaxSize().safeDrawingPadding().verticalScroll(rememberScrollState()).padding(horizontal=24.dp).padding(bottom=28.dp)) {
                Row(Modifier.fillMaxWidth().padding(vertical=24.dp), verticalAlignment=Alignment.CenterVertically) {
                    Icon(Icons.Rounded.PlayCircle, null, Modifier.size(34.dp), tint=MaterialTheme.colorScheme.primary)
                    Text("navelo", Modifier.padding(start=8.dp).weight(1f), fontSize=29.sp, fontWeight=FontWeight.Bold, letterSpacing=(-1).sp)
                    if (onboarded && screen == "home") IconButton(onClick={ screen="settings" }) { Icon(Icons.Rounded.Settings, "Settings") }
                    if (screen != "home") TextButton(onClick={ screen="home" }) { Text("Done") }
                }
                if (!onboarded) {
                    AnimatedContent(targetState=step, label="Setup step") { page ->
                        Column(verticalArrangement=Arrangement.spacedBy(24.dp)) {
                            Text("${page + 1} / 3", style=MaterialTheme.typography.labelLarge, color=MaterialTheme.colorScheme.primary)
                            when(page) {
                                0 -> {
                                    BrandPanel()
                                    Headline("Your media,\non your TV", "Movies and shows on this phone. A beautiful, simple experience on your television.")
                                    Benefit(Icons.Rounded.HighQuality, "Every detail, preserved", "Your videos play in their original quality.")
                                    Benefit(Icons.Rounded.PrivacyTip, "Just between your devices", "No account, no media uploads, no ads.")
                                    Button(onClick={step=1}, modifier=Modifier.fillMaxWidth().heightIn(min=56.dp)) { Text("Continue") }
                                }
                                1 -> {
                                    Headline("Choose your media", "Choose the folders where you keep your movies and TV shows. You can add more later.")
                                    FolderChoice("Movies", Icons.Rounded.Movie, state.roots.filter { it.type==RootType.MOVIES }, {choose(RootType.MOVIES)})
                                    FolderChoice("TV Shows", Icons.Rounded.Tv, state.roots.filter { it.type==RootType.TV_SHOWS }, {choose(RootType.TV_SHOWS)})
                                    TextButton(onClick={choose(RootType.OTHER)}) { Icon(Icons.Rounded.Add,null); Spacer(Modifier.width(8.dp)); Text("Choose another folder") }
                                    Button(onClick={step=2}, enabled=state.roots.isNotEmpty(), modifier=Modifier.fillMaxWidth().heightIn(min=56.dp)) { Text("Continue") }
                                    if(state.roots.isEmpty()) Text("Choose at least one folder to continue.", style=MaterialTheme.typography.bodyMedium)
                                }
                                else -> {
                                    Icon(Icons.Rounded.CheckCircle, null, Modifier.size(76.dp), tint=MaterialTheme.colorScheme.primary)
                                    Headline("Ready for movie night", "Connect your TV to the same Wi-Fi as this phone, or to this phone's hotspot. Then open Navelo on your TV.")
                                    Benefit(Icons.Rounded.TouchApp, "Connect once", "Choose this phone on your TV. Tap Allow here when it asks to connect.")
                                    Benefit(Icons.Rounded.AutoAwesome, "Simple from then on", "Open Navelo on your TV, choose something you love, and press Play.")
                                    Button(onClick={begin()}, modifier=Modifier.fillMaxWidth().heightIn(min=56.dp)) { Text("Start Navelo") }
                                }
                            }
                        }
                    }
                } else when(screen) {
                    "home" -> {
                        StatusPanel(state)
                        Spacer(Modifier.height(28.dp))
                        if(state.streamingTitle != null) {
                            SectionLabel("NOW PLAYING")
                            ListItem(headlineContent={Text(state.streamingTitle!!, fontWeight=FontWeight.SemiBold)}, supportingContent={Text("Playing on your TV")}, leadingContent={Icon(Icons.Rounded.PlayCircle,null)})
                            Spacer(Modifier.height(20.dp))
                        }
                        state.pending.forEach { pending ->
                            PairCard(pending, { scope.launch { runtime.approve(pending.requestId) } }, { scope.launch { runtime.deny(pending.requestId) } })
                            Spacer(Modifier.height(16.dp))
                        }
                        Row(Modifier.fillMaxWidth(), verticalAlignment=Alignment.CenterVertically) {
                            Text("Your TV", style=MaterialTheme.typography.titleLarge, modifier=Modifier.weight(1f))
                            TextButton(onClick={screen="tvs"}) { Text("Manage") }
                        }
                        if(state.devices.isEmpty()) {
                            Benefit(Icons.Rounded.Tv, "Ready to meet your TV", "Open Navelo on your TV and choose this phone. Both devices can use your Wi-Fi or this phone's hotspot.")
                        } else state.devices.forEach { device ->
                            ListItem(headlineContent={Text(device.displayName, fontWeight=FontWeight.SemiBold)}, supportingContent={Text("Paired · reconnects automatically")}, leadingContent={Icon(Icons.Rounded.Tv,null)})
                        }
                        Spacer(Modifier.height(28.dp))
                        Row(Modifier.fillMaxWidth(), verticalAlignment=Alignment.CenterVertically) {
                            Text("Your media", style=MaterialTheme.typography.titleLarge, modifier=Modifier.weight(1f))
                            TextButton(onClick={screen="media"}) { Text("Manage") }
                        }
                        if(state.roots.isEmpty()) {
                            Benefit(Icons.Rounded.FolderOpen, "A home for your favourites", "Add a folder of movies or shows to get started.")
                            Button(onClick={screen="media"}) {Text("Choose media")}
                        } else state.roots.forEach { root -> RootRow(root) }
                        if(state.scanning) { Spacer(Modifier.height(12.dp)); LinearProgressIndicator(Modifier.fillMaxWidth()); Text("Finding your videos…", Modifier.padding(top=8.dp), style=MaterialTheme.typography.bodyMedium) }
                        Spacer(Modifier.height(28.dp))
                        if(state.running) OutlinedButton(onClick={runtime.stop()}, modifier=Modifier.fillMaxWidth().heightIn(min=52.dp)) { Text("Pause sharing") }
                        else Button(onClick={begin()}, enabled=state.roots.isNotEmpty(), modifier=Modifier.fillMaxWidth().heightIn(min=56.dp)) { Text("Start Navelo") }
                    }
                    "tvs" -> {
                        Headline("Your TV", "Manage the TVs allowed to play your media. A removed TV will need your approval to connect again.")
                        Spacer(Modifier.height(20.dp))
                        var removingTv by remember { mutableStateOf<String?>(null) }
                        var tvFeedback by remember { mutableStateOf<String?>(null) }
                        if (state.devices.isEmpty()) {
                            Benefit(Icons.Rounded.Tv, "No paired TVs", "Open Navelo on your TV and choose this phone to connect.")
                        }
                        state.devices.forEach { device ->
                            ListItem(
                                headlineContent={Text(device.displayName, fontWeight=FontWeight.SemiBold)},
                                supportingContent={Text("Allowed to play your media")},
                                leadingContent={Icon(Icons.Rounded.Tv, null)},
                                trailingContent={TextButton(enabled=removingTv == null, onClick={scope.launch {
                                    removingTv = device.clientId
                                    runCatching { runtime.revoke(device.clientId) }
                                        .onSuccess { tvFeedback = "${device.displayName} removed" }
                                        .onFailure { tvFeedback = "Could not remove this TV. Please try again." }
                                    removingTv = null
                                }}) { Text(if (removingTv == device.clientId) "Removing…" else "Remove") }},
                            )
                        }
                        tvFeedback?.let { Text(it, Modifier.padding(top=12.dp)) }
                    }
                    "media" -> {
                        Headline("Your media", "Add folders from this phone or an SD card. Your files stay right where they are.")
                        Spacer(Modifier.height(20.dp))
                        state.roots.forEach { root ->
                            RootRow(root, { removal=root })
                        }
                        Spacer(Modifier.height(20.dp))
                        RootType.entries.forEach { type ->
                            OutlinedButton(onClick={choose(type)}, modifier=Modifier.fillMaxWidth().heightIn(min=52.dp)) {
                                Icon(Icons.Rounded.Add, null); Spacer(Modifier.width(8.dp)); Text("Add ${rootLabel(type).lowercase()} folder")
                            }
                            Spacer(Modifier.height(8.dp))
                        }
                        TextButton(onClick={scope.launch{runtime.rescan()}}, enabled=!state.scanning) { Icon(Icons.Rounded.Refresh,null); Spacer(Modifier.width(8.dp)); Text(if(state.scanning) "Finding videos…" else "Find new videos") }
                    }
                    "settings" -> {
                        Headline("Settings", "A few things to make Navelo yours.")
                        Spacer(Modifier.height(24.dp)); SectionLabel("SERVER NAME")
                        var nameDraft by remember(state.displayName) { mutableStateOf(state.displayName) }
                        var nameFeedback by remember { mutableStateOf<String?>(null) }
                        var savingName by remember { mutableStateOf(false) }
                        OutlinedTextField(
                            value = nameDraft, onValueChange = { nameDraft = it; nameFeedback = null },
                            label = { Text("Server name") }, singleLine = true,
                            modifier = Modifier.fillMaxWidth(), enabled = !savingName,
                            supportingText = { Text("Shown on your TV · up to 60 characters") },
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(enabled = !savingName && state.serverId.isNotBlank(), onClick = { scope.launch {
                                savingName = true
                                runCatching { runtime.renameServer(nameDraft) }
                                    .onSuccess { nameFeedback = "Server name saved" }
                                    .onFailure { nameFeedback = it.message ?: "Could not save the name" }
                                savingName = false
                            } }) { Text(if (savingName) "Saving…" else "Save name") }
                            TextButton(enabled = !savingName && state.customServerName, onClick = { scope.launch {
                                savingName = true
                                runCatching { runtime.renameServer(null) }
                                    .onSuccess { nameFeedback = "Using device name" }
                                    .onFailure { nameFeedback = it.message ?: "Could not reset the name" }
                                savingName = false
                            } }) { Text("Use device name") }
                        }
                        nameFeedback?.let { Text(it, Modifier.padding(top = 8.dp)) }
                        Spacer(Modifier.height(24.dp)); SectionLabel("APPEARANCE")
                        Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                            listOf("System","Light","Dark").forEach { value -> FilterChip(selected=appearance==value, onClick={scope.launch {
                                context.uiStore.edit { it[appearanceKey]=value }
                                context.applyApplicationAppearance(value)
                            }}, label={Text(value)}) }
                        }
                        if(Build.VERSION.SDK_INT>=31) ListItem(headlineContent={Text("Use phone colours")}, trailingContent={Switch(checked=dynamic,onCheckedChange={value -> scope.launch{context.uiStore.edit { it[dynamicKey]=value }} })})
                        Spacer(Modifier.height(24.dp)); SectionLabel("PRIVACY")
                        Text("Your videos stay on this phone. Navelo sends them directly to your paired TV. Only movie and show searches for artwork leave your local network. Your watch history stays on your TV. No account, advertising or analytics.", Modifier.padding(vertical=12.dp), style=MaterialTheme.typography.bodyLarge)
                        TextButton(onClick={screen="advanced"}){Text("Advanced")}
                        Text("Navelo ${BuildConfig.VERSION_NAME} · Made for movie night", Modifier.padding(top=20.dp), style=MaterialTheme.typography.bodySmall)
                    }
                    "advanced" -> {
                        Headline("Connection details", "For the person who looks after your devices.")
                        Spacer(Modifier.height(20.dp))
                        Text("Connection: ${if(state.running) "Sharing is on" else "Sharing is paused"}\nPhone identity: ${state.serverId}\nListening port: ${state.port}\nDiscovery: local Wi-Fi and phone hotspot", style=MaterialTheme.typography.bodyLarge)
                        Spacer(Modifier.height(20.dp))
                        Text("Use a private Wi-Fi network or a password-protected phone hotspot. Connections are approved on this phone. Videos travel directly over the local network without transport encryption.")
                        Spacer(Modifier.height(20.dp))
                        Text("Keep the phone charged for long viewing sessions. Some phones pause background apps to save power. If sharing stops while the phone is locked, allow Navelo to run in the background in your phone's app settings.")
                        OutlinedButton(onClick={context.startActivity(Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}")))}, modifier=Modifier.padding(top=16.dp)){Text("Open phone app settings")}
                    }
                }
                val message = error ?: state.message
                if(message != null) {
                    Spacer(Modifier.height(20.dp))
                    Surface(color=MaterialTheme.colorScheme.errorContainer, shape=RoundedCornerShape(16.dp)) {
                        Row(Modifier.padding(16.dp), verticalAlignment=Alignment.CenterVertically) { Icon(Icons.Rounded.Info,null); Text(message, Modifier.padding(start=12.dp).weight(1f)) }
                    }
                }
            }
            removal?.let { root -> AlertDialog(onDismissRequest={removal=null}, title={Text("Remove ${root.name}?")}, text={Text("It will disappear from Navelo. Your files will stay on this phone.")}, confirmButton={TextButton(onClick={scope.launch { runtime.removeRoot(root.id) }; removal=null}){Text("Remove folder")}}, dismissButton={TextButton(onClick={removal=null}){Text("Keep folder")}}) }
        }
    }
}

@Composable private fun BrandPanel() {
    Box(Modifier.fillMaxWidth().height(180.dp).background(Brush.linearGradient(listOf(Color(0xFF153D42),Color(0xFF0C1A2D))),RoundedCornerShape(28.dp)), contentAlignment=Alignment.Center) {
        Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(20.dp)) {
            Icon(Icons.Rounded.PhoneAndroid,null,Modifier.size(44.dp),tint=Color(0xFF9FDEC9))
            Icon(Icons.Rounded.Wifi,null,Modifier.size(28.dp),tint=Color(0xFF9FDEC9))
            Icon(Icons.Rounded.LiveTv,null,Modifier.size(80.dp),tint=Color(0xFFE1F4ED))
        }
    }
}
@Composable private fun Headline(title:String, subtitle:String) {
    Column(verticalArrangement=Arrangement.spacedBy(12.dp)) {
        Text(title,style=MaterialTheme.typography.headlineLarge,fontWeight=FontWeight.Bold,letterSpacing=(-0.7).sp)
        Text(subtitle,style=MaterialTheme.typography.bodyLarge,color=MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
@Composable private fun Benefit(icon:ImageVector,title:String,body:String) {
    Row(Modifier.padding(vertical=12.dp),horizontalArrangement=Arrangement.spacedBy(16.dp)) {
        Icon(icon,null,Modifier.padding(top=3.dp).size(28.dp),tint=MaterialTheme.colorScheme.primary)
        Column { Text(title,style=MaterialTheme.typography.titleMedium); Spacer(Modifier.height(4.dp)); Text(body,style=MaterialTheme.typography.bodyMedium,color=MaterialTheme.colorScheme.onSurfaceVariant) }
    }
}
@Composable private fun FolderChoice(title:String,icon:ImageVector,roots:List<MediaRoot>,onChoose:()->Unit) {
    OutlinedCard(Modifier.fillMaxWidth()) { Column(Modifier.padding(20.dp)) {
        Row(verticalAlignment=Alignment.CenterVertically) { Icon(icon,null,tint=MaterialTheme.colorScheme.primary); Text(title,Modifier.padding(start=12.dp),style=MaterialTheme.typography.titleLarge) }
        roots.forEach { Text(it.name,Modifier.padding(top=12.dp),style=MaterialTheme.typography.bodyLarge) }
        OutlinedButton(onClick=onChoose,modifier=Modifier.padding(top=16.dp).heightIn(min=48.dp)) { Text(if(roots.isEmpty()) "Choose folder" else "Add another folder") }
    } }
}
@Composable private fun StatusPanel(state:ServerState) {
    val title = if(state.running) "Ready for your TV" else "Taking a pause"
    val subtitle = if(state.running) "Your media is available on your Wi-Fi." else "Start Navelo when you're ready to watch."
    Surface(shape=RoundedCornerShape(24.dp),color=MaterialTheme.colorScheme.primaryContainer) {
        Column(Modifier.fillMaxWidth().padding(24.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
            Icon(if(state.running) Icons.Rounded.CheckCircle else Icons.Rounded.PauseCircle,null,Modifier.size(36.dp))
            Text(title,style=MaterialTheme.typography.headlineMedium,fontWeight=FontWeight.SemiBold)
            Text(subtitle,style=MaterialTheme.typography.bodyLarge)
        }
    }
}
@Composable private fun PairCard(pair:PendingPair,allow:()->Unit,deny:()->Unit) {
    ElevatedCard { Column(Modifier.fillMaxWidth().padding(20.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
        Icon(Icons.Rounded.Tv,null,tint=MaterialTheme.colorScheme.primary)
        Text("${pair.displayName} wants to connect",style=MaterialTheme.typography.titleLarge)
        Text("Check that your TV shows ${pair.pin}. Allow it to watch your media?")
        Row(horizontalArrangement=Arrangement.spacedBy(12.dp)) { Button(onClick=allow,modifier=Modifier.heightIn(min=48.dp)){Text("Allow")}; TextButton(onClick=deny,modifier=Modifier.heightIn(min=48.dp)){Text("Not now")} }
    } }
}
@Composable private fun RootRow(root:MediaRoot,remove:(()->Unit)?=null) {
    ListItem(headlineContent={Text(root.name,fontWeight=FontWeight.Medium)},supportingContent={Text(if(root.available) "${rootLabel(root.type)} · ${root.itemCount} videos" else "Folder unavailable · reconnect storage")},leadingContent={Icon(if(root.type==RootType.TV_SHOWS) Icons.Rounded.Tv else Icons.Rounded.Movie,null)},trailingContent={if(remove!=null) IconButton(onClick=remove){Icon(Icons.Rounded.RemoveCircleOutline,"Remove ${root.name}")}})
}
@Composable private fun SectionLabel(text:String) { Text(text,style=MaterialTheme.typography.labelMedium,letterSpacing=1.8.sp,color=MaterialTheme.colorScheme.onSurfaceVariant,modifier=Modifier.padding(bottom=8.dp)) }
private fun rootLabel(type:RootType)=when(type){RootType.MOVIES->"Movies";RootType.TV_SHOWS->"TV shows";RootType.OTHER->"Other videos"}
