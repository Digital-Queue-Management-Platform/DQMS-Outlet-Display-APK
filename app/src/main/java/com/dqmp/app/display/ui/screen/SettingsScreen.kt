package com.dqmp.app.display.ui.screen

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.dqmp.app.display.R
import com.dqmp.app.display.model.DisplaySettings
import com.dqmp.app.display.ui.theme.ThemeProvider
import com.dqmp.app.display.viewmodel.SettingsViewModel
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onBackClick: () -> Unit,
    onResetConfiguration: () -> Unit = {},
    modifier: Modifier = Modifier,
    viewModel: SettingsViewModel = viewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    val settings = uiState.settings
    val theme = ThemeProvider.getTheme(settings.theme)
    
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    colors = listOf(theme.background, theme.surface),
                    startY = 0f,
                    endY = Float.POSITIVE_INFINITY
                )
            )
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(32.dp)
        ) {
            // Header
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = stringResource(R.string.settings_title),
                    color = theme.text,
                    fontSize = 28.sp,
                    fontWeight = FontWeight.Bold
                )
                
                Button(
                    onClick = onBackClick,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = theme.secondary
                    )
                ) {
                    Text(stringResource(R.string.back))
                }
            }
            
            Spacer(modifier = Modifier.height(32.dp))
            
            // Settings sections
            SettingsSection(
                title = stringResource(R.string.connection_settings),
                theme = theme
            ) {
                ConnectionSettings(
                    settings = settings,
                    onSettingsChange = viewModel::updateSettings,
                    theme = theme
                )
            }
            
            Spacer(modifier = Modifier.height(24.dp))
            
            SettingsSection(
                title = stringResource(R.string.display_settings),
                theme = theme
            ) {
                DisplaySettings(
                    settings = settings,
                    onSettingsChange = viewModel::updateSettings,
                    theme = theme
                )
            }
            
            Spacer(modifier = Modifier.height(24.dp))
            
            SettingsSection(
                title = stringResource(R.string.audio_settings),
                theme = theme
            ) {
                AudioSettings(
                    settings = settings,
                    onSettingsChange = viewModel::updateSettings,
                    onTestAnnouncement = viewModel::testAnnouncement,
                    onPlayChime = viewModel::playChimeOnly,
                    onPlayVoice = viewModel::playVoiceMessage,
                    onPlayAllLanguages = viewModel::playAllLanguagesSequentially,
                    onTranslateEnglish = viewModel::translateFromEnglish,
                    theme = theme
                )
            }
            
            Spacer(modifier = Modifier.height(24.dp))
            
            // Configuration Management
            if (settings.isConfigured) {
                SettingsSection(
                    title = stringResource(R.string.configuration_management),
                    theme = theme
                ) {
                    ConfigurationManagement(
                        outletName = settings.outletName,
                        outletId = settings.outletId,
                        onResetConfiguration = onResetConfiguration,
                        theme = theme
                    )
                }
            }
        }
    }
}

@Composable
private fun SettingsSection(
    title: String,
    theme: com.dqmp.app.display.ui.theme.AppTheme,
    content: @Composable () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = theme.surface.copy(alpha = 0.8f)
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(24.dp)
        ) {
            Text(
                text = title,
                color = theme.text,
                fontSize = 20.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(bottom = 16.dp)
            )
            
            content()
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ConnectionSettings(
    settings: DisplaySettings,
    onSettingsChange: (DisplaySettings) -> Unit,
    theme: com.dqmp.app.display.ui.theme.AppTheme
) {
    var serverUrl by remember(settings.serverUrl) { mutableStateOf(settings.serverUrl) }
    var outletId by remember(settings.outletId) { mutableStateOf(settings.outletId) }
    
    Column(
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        OutlinedTextField(
            value = serverUrl,
            onValueChange = { 
                serverUrl = it
                onSettingsChange(settings.copy(serverUrl = it))
            },
            label = { Text(stringResource(R.string.server_url)) },
            modifier = Modifier.fillMaxWidth(),
            colors = TextFieldDefaults.outlinedTextFieldColors(
                focusedTextColor = theme.text,
                unfocusedTextColor = theme.textSecondary,
                focusedBorderColor = theme.accent,
                unfocusedBorderColor = theme.textSecondary.copy(alpha = 0.5f)
            )
        )
        
        OutlinedTextField(
            value = outletId,
            onValueChange = { 
                outletId = it
                onSettingsChange(settings.copy(outletId = it))
            },
            label = { Text(stringResource(R.string.outlet_id)) },
            modifier = Modifier.fillMaxWidth(),
            colors = TextFieldDefaults.outlinedTextFieldColors(
                focusedTextColor = theme.text,
                unfocusedTextColor = theme.textSecondary,
                focusedBorderColor = theme.accent,
                unfocusedBorderColor = theme.textSecondary.copy(alpha = 0.5f)
            )
        )
    }
}

@Composable
private fun DisplaySettings(
    settings: DisplaySettings,
    onSettingsChange: (DisplaySettings) -> Unit,
    theme: com.dqmp.app.display.ui.theme.AppTheme
) {
    Column(
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Language Selection
        LanguageSelector(
            selectedLanguage = settings.language,
            onLanguageChange = { language ->
                onSettingsChange(settings.copy(language = language))
            },
            theme = theme
        )
        
        // Theme Selection
        ThemeSelector(
            selectedTheme = settings.theme,
            onThemeChange = { theme ->
                onSettingsChange(settings.copy(theme = theme))
            },
            theme = theme
        )
        
        // Show Queue Numbers Toggle
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = stringResource(R.string.show_queue_numbers),
                color = theme.text,
                fontSize = 16.sp
            )
            
            Switch(
                checked = settings.showQueueNumbers,
                onCheckedChange = { enabled ->
                    onSettingsChange(settings.copy(showQueueNumbers = enabled))
                },
                colors = SwitchDefaults.colors(
                    checkedThumbColor = theme.accent,
                    checkedTrackColor = theme.accent.copy(alpha = 0.5f)
                )
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LanguageSelector(
    selectedLanguage: String,
    onLanguageChange: (String) -> Unit,
    theme: com.dqmp.app.display.ui.theme.AppTheme
) {
    val languages = listOf(
        "en" to "English",
        "si" to "සිංහල",
        "ta" to "தமிழ்"
    )
    
    Column {
        Text(
            text = stringResource(R.string.language),
            color = theme.text,
            fontSize = 16.sp,
            modifier = Modifier.padding(bottom = 8.dp)
        )
        
        Row(
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            languages.forEach { (code, name) ->
                FilterChip(
                    onClick = { onLanguageChange(code) },
                    label = { Text(name) },
                    selected = selectedLanguage == code,
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = theme.accent,
                        selectedLabelColor = theme.text
                    )
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ThemeSelector(
    selectedTheme: String,
    onThemeChange: (String) -> Unit,
    theme: com.dqmp.app.display.ui.theme.AppTheme
) {
    val themes = listOf(
        "emerald" to "Emerald",
        "sky" to "Sky",
        "indigo" to "Indigo",
        "slate" to "Slate"
    )
    
    Column {
        Text(
            text = stringResource(R.string.theme),
            color = theme.text,
            fontSize = 16.sp,
            modifier = Modifier.padding(bottom = 8.dp)
        )
        
        Row(
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            themes.forEach { (code, name) ->
                FilterChip(
                    onClick = { onThemeChange(code) },
                    label = { Text(name) },
                    selected = selectedTheme == code,
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = theme.accent,
                        selectedLabelColor = theme.text
                    )
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AudioSettings(
    settings: DisplaySettings,
    onSettingsChange: (DisplaySettings) -> Unit,
    onTestAnnouncement: () -> Unit,
    onPlayChime: () -> Unit,
    onPlayVoice: (text: String, language: String) -> Unit,
    onPlayAllLanguages: (english: String, sinhala: String, tamil: String) -> Unit,
    onTranslateEnglish: suspend (text: String, target: String) -> String?,
    theme: com.dqmp.app.display.ui.theme.AppTheme
) {
    val scope = rememberCoroutineScope()
    var customEn by rememberSaveable { mutableStateOf("") }
    var customSi by rememberSaveable { mutableStateOf("") }
    var customTa by rememberSaveable { mutableStateOf("") }
    var isTranslating by rememberSaveable { mutableStateOf(false) }

    Column(
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Enable Announcements Toggle
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = stringResource(R.string.enable_announcements),
                color = theme.text,
                fontSize = 16.sp
            )
            
            Switch(
                checked = settings.enableAnnouncements,
                onCheckedChange = { enabled ->
                    onSettingsChange(settings.copy(enableAnnouncements = enabled))
                },
                colors = SwitchDefaults.colors(
                    checkedThumbColor = theme.accent,
                    checkedTrackColor = theme.accent.copy(alpha = 0.5f)
                )
            )
        }
        
        // Volume Slider
        Column {
            Text(
                text = stringResource(R.string.volume),
                color = theme.text,
                fontSize = 16.sp,
                modifier = Modifier.padding(bottom = 8.dp)
            )
            
            Slider(
                value = settings.volume,
                onValueChange = { volume ->
                    onSettingsChange(settings.copy(volume = volume))
                },
                valueRange = 0f..1f,
                colors = SliderDefaults.colors(
                    thumbColor = theme.accent,
                    activeTrackColor = theme.accent
                ),
                modifier = Modifier.fillMaxWidth()
            )
            
            Text(
                text = "${(settings.volume * 100).toInt()}%",
                color = theme.textSecondary,
                fontSize = 14.sp
            )
        }
        
        // Test Announcement Button
        Button(
            onClick = onTestAnnouncement,
            colors = ButtonDefaults.buttonColors(
                containerColor = theme.primary
            ),
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(stringResource(R.string.test_announcement))
        }

        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedButton(
                onClick = onPlayChime,
                modifier = Modifier.weight(1f)
            ) {
                Text("Play Chime")
            }
            OutlinedButton(
                onClick = { onPlayVoice("This is a speaker test announcement.", settings.language) },
                modifier = Modifier.weight(1f)
            ) {
                Text("Play Voice")
            }
        }

        Divider(color = theme.textSecondary.copy(alpha = 0.2f))

        Text(
            text = "Manual Text Announcement (Multi-Language)",
            color = theme.text,
            fontSize = 16.sp,
            fontWeight = FontWeight.SemiBold
        )

        OutlinedTextField(
            value = customEn,
            onValueChange = { customEn = it },
            label = { Text("English") },
            placeholder = { Text("Type English message...") },
            modifier = Modifier.fillMaxWidth(),
            colors = TextFieldDefaults.outlinedTextFieldColors(
                focusedTextColor = theme.text,
                unfocusedTextColor = theme.textSecondary,
                focusedBorderColor = theme.accent,
                unfocusedBorderColor = theme.textSecondary.copy(alpha = 0.5f)
            )
        )

        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedButton(
                onClick = {
                    if (customEn.isBlank() || isTranslating) return@OutlinedButton
                    scope.launch {
                        isTranslating = true
                        val si = onTranslateEnglish(customEn, "si")
                        val ta = onTranslateEnglish(customEn, "ta")
                        if (!si.isNullOrBlank()) customSi = si
                        if (!ta.isNullOrBlank()) customTa = ta
                        isTranslating = false
                    }
                },
                modifier = Modifier.weight(1f),
                enabled = customEn.isNotBlank() && !isTranslating
            ) {
                Text(if (isTranslating) "Translating..." else "Translate")
            }
            OutlinedButton(
                onClick = { if (customEn.isNotBlank()) onPlayVoice(customEn, "en") },
                modifier = Modifier.weight(1f),
                enabled = customEn.isNotBlank()
            ) {
                Text("Play EN")
            }
        }

        OutlinedTextField(
            value = customSi,
            onValueChange = { customSi = it },
            label = { Text("සිංහල") },
            placeholder = { Text("සිංහල නිවේදනය...") },
            modifier = Modifier.fillMaxWidth(),
            colors = TextFieldDefaults.outlinedTextFieldColors(
                focusedTextColor = theme.text,
                unfocusedTextColor = theme.textSecondary,
                focusedBorderColor = theme.accent,
                unfocusedBorderColor = theme.textSecondary.copy(alpha = 0.5f)
            )
        )

        OutlinedButton(
            onClick = { if (customSi.isNotBlank()) onPlayVoice(customSi, "si") },
            modifier = Modifier.fillMaxWidth(),
            enabled = customSi.isNotBlank()
        ) {
            Text("Play SI")
        }

        OutlinedTextField(
            value = customTa,
            onValueChange = { customTa = it },
            label = { Text("தமிழ்") },
            placeholder = { Text("தமிழ் அறிவிப்பு...") },
            modifier = Modifier.fillMaxWidth(),
            colors = TextFieldDefaults.outlinedTextFieldColors(
                focusedTextColor = theme.text,
                unfocusedTextColor = theme.textSecondary,
                focusedBorderColor = theme.accent,
                unfocusedBorderColor = theme.textSecondary.copy(alpha = 0.5f)
            )
        )

        OutlinedButton(
            onClick = { if (customTa.isNotBlank()) onPlayVoice(customTa, "ta") },
            modifier = Modifier.fillMaxWidth(),
            enabled = customTa.isNotBlank()
        ) {
            Text("Play TA")
        }

        Button(
            onClick = { onPlayAllLanguages(customEn, customSi, customTa) },
            modifier = Modifier.fillMaxWidth(),
            enabled = customEn.isNotBlank() || customSi.isNotBlank() || customTa.isNotBlank(),
            colors = ButtonDefaults.buttonColors(containerColor = theme.accent)
        ) {
            Text("PLAY ALL LANGUAGES SEQUENTIALLY")
        }
    }
}

@Composable
private fun ConfigurationManagement(
    outletName: String,
    outletId: String,
    onResetConfiguration: () -> Unit,
    theme: com.dqmp.app.display.ui.theme.AppTheme
) {
    var showResetDialog by remember { mutableStateOf(false) }
    
    Column(
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Current Configuration Info
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = theme.primary.copy(alpha = 0.1f)
            )
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = stringResource(R.string.current_configuration),
                    color = theme.text,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.SemiBold
                )
                
                Text(
                    text = stringResource(R.string.outlet_name_label, outletName),
                    color = theme.textSecondary,
                    fontSize = 14.sp
                )
                
                Text(
                    text = stringResource(R.string.outlet_id_label, outletId),
                    color = theme.textSecondary,
                    fontSize = 14.sp
                )
            }
        }
        
        // Reset Configuration Button
        OutlinedButton(
            onClick = { showResetDialog = true },
            colors = ButtonDefaults.outlinedButtonColors(
                contentColor = Color.Red
            ),
            border = ButtonDefaults.outlinedButtonBorder.copy(
                brush = androidx.compose.ui.graphics.SolidColor(Color.Red)
            ),
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(stringResource(R.string.configuration_reset))
        }
    }
    
    // Reset Confirmation Dialog
    if (showResetDialog) {
        AlertDialog(
            onDismissRequest = { showResetDialog = false },
            title = {
                Text(
                    text = stringResource(R.string.confirm_reset),
                    color = theme.text
                )
            },
            text = {
                Text(
                    text = stringResource(R.string.reset_confirmation),
                    color = theme.textSecondary
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showResetDialog = false
                        onResetConfiguration()
                    },
                    colors = ButtonDefaults.textButtonColors(
                        contentColor = Color.Red
                    )
                ) {
                    Text(stringResource(R.string.reset))
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { showResetDialog = false },
                    colors = ButtonDefaults.textButtonColors(
                        contentColor = theme.textSecondary
                    )
                ) {
                    Text(stringResource(R.string.cancel))
                }
            },
            containerColor = theme.surface
        )
    }
}