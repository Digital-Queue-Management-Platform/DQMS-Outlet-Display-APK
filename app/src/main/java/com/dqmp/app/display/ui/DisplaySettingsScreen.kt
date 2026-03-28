package com.dqmp.app.display.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dqmp.app.display.data.DisplaySettings
import com.dqmp.app.display.ui.theme.*

@Composable
fun DisplaySettingsScreen(
    currentSettings: DisplaySettings,
    onSettingsChange: (DisplaySettings) -> Unit,
    onClose: () -> Unit
) {
    var refresh by remember { mutableStateOf(currentSettings.refresh ?: 10) }
    var next by remember { mutableStateOf(currentSettings.next ?: 8) }
    var services by remember { mutableStateOf(currentSettings.services ?: true) }
    var counters by remember { mutableStateOf(currentSettings.counters ?: true) }
    var recent by remember { mutableStateOf(currentSettings.recent ?: true) }
    var autoSlide by remember { mutableStateOf(currentSettings.autoSlide ?: true) }
    var playTone by remember { mutableStateOf(currentSettings.playTone ?: true) }
    var contentScale by remember { mutableStateOf(currentSettings.contentScale ?: 100) }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.7f)),
        contentAlignment = Alignment.Center
    ) {
        Card(
            modifier = Modifier
                .fillMaxWidth(0.8f)
                .fillMaxHeight(0.9f),
            shape = RoundedCornerShape(24.dp),
            colors = CardDefaults.cardColors(containerColor = Color.White),
            elevation = CardDefaults.cardElevation(defaultElevation = 8.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(24.dp)
            ) {
                // Header
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Icon(
                            Icons.Default.Settings,
                            contentDescription = null,
                            tint = Emerald500,
                            modifier = Modifier.size(32.dp)
                        )
                        Text(
                            text = "Display Settings",
                            color = Slate900,
                            fontSize = 24.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }

                    IconButton(onClick = onClose) {
                        Icon(
                            Icons.Default.Close,
                            contentDescription = "Close",
                            tint = Slate500
                        )
                    }
                }

                Spacer(modifier = Modifier.height(24.dp))

                // Settings Content
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(20.dp)
                ) {
                    // Display Options Section
                    SettingsSection(
                        title = "Display Options",
                        icon = Icons.Default.Visibility
                    ) {
                        SettingSlider(
                            title = "Refresh Rate",
                            description = "How often to update data (seconds)",
                            value = refresh.toFloat(),
                            onValueChange = { refresh = it.toInt() },
                            valueRange = 5f..60f,
                            steps = 10,
                            displayValue = "${refresh}s"
                        )

                        SettingSlider(
                            title = "Up Next Count",
                            description = "Number of waiting tokens to show",
                            value = next.toFloat(),
                            onValueChange = { next = it.toInt() },
                            valueRange = 3f..20f,
                            steps = 16,
                            displayValue = next.toString()
                        )

                        SettingSlider(
                            title = "Content Scale",
                            description = "Zoom level for the entire display",
                            value = contentScale.toFloat(),
                            onValueChange = { contentScale = it.toInt() },
                            valueRange = 50f..200f,
                            steps = 14,
                            displayValue = "${contentScale}%"
                        )
                    }

                    // Feature Toggles Section
                    SettingsSection(
                        title = "Feature Toggles",
                        icon = Icons.Default.ToggleOn
                    ) {
                        SettingToggle(
                            title = "Service Type Badges",
                            description = "Show service category badges on tokens",
                            checked = services,
                            onCheckedChange = { services = it }
                        )

                        SettingToggle(
                            title = "Counter Status Panel",
                            description = "Display counter and officer information",
                            checked = counters,
                            onCheckedChange = { counters = it }
                        )

                        SettingToggle(
                            title = "Recent Calls History",
                            description = "Show recently called tokens",
                            checked = recent,
                            onCheckedChange = { recent = it }
                        )

                        SettingToggle(
                            title = "Auto-Slide Cards",
                            description = "Automatically scroll through items",
                            checked = autoSlide,
                            onCheckedChange = { autoSlide = it }
                        )
                    }

                    // Audio Settings Section
                    SettingsSection(
                        title = "Audio Settings",
                        icon = Icons.Default.VolumeUp
                    ) {
                        SettingToggle(
                            title = "Play Chime",
                            description = "Play sound before voice announcements",
                            checked = playTone,
                            onCheckedChange = { playTone = it }
                        )
                    }
                }

                // Footer Actions
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    OutlinedButton(
                        onClick = onClose,
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Text("Cancel")
                    }

                    Button(
                        onClick = {
                            val newSettings = DisplaySettings(
                                refresh = refresh,
                                next = next,
                                services = services,
                                counters = counters,
                                recent = recent,
                                autoSlide = autoSlide,
                                playTone = playTone,
                                contentScale = contentScale
                            )
                            onSettingsChange(newSettings)
                            onClose()
                        },
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.buttonColors(containerColor = Emerald500),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Text("Save Settings", color = Color.White)
                    }
                }
            }
        }
    }
}

@Composable
fun SettingsSection(
    title: String,
    icon: ImageVector,
    content: @Composable ColumnScope.() -> Unit
) {
    Card(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = Slate50),
        border = androidx.compose.foundation.BorderStroke(1.dp, Slate200)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Section Header
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(
                    icon,
                    contentDescription = null,
                    tint = Emerald500,
                    modifier = Modifier.size(20.dp)
                )
                Text(
                    text = title,
                    color = Slate900,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.SemiBold
                )
            }

            // Section Content
            content()
        }
    }
}

@Composable
fun SettingToggle(
    title: String,
    description: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                color = Slate900,
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium
            )
            Text(
                text = description,
                color = Slate600,
                fontSize = 12.sp,
                fontWeight = FontWeight.Normal
            )
        }

        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = Color.White,
                checkedTrackColor = Emerald500,
                uncheckedThumbColor = Color.White,
                uncheckedTrackColor = Slate300
            )
        )
    }
}

@Composable
fun SettingSlider(
    title: String,
    description: String,
    value: Float,
    onValueChange: (Float) -> Unit,
    valueRange: ClosedFloatingPointRange<Float>,
    steps: Int = 0,
    displayValue: String
) {
    Column(
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    color = Slate900,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium
                )
                Text(
                    text = description,
                    color = Slate600,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Normal
                )
            }

            Card(
                shape = RoundedCornerShape(8.dp),
                colors = CardDefaults.cardColors(containerColor = Emerald50),
                border = androidx.compose.foundation.BorderStroke(1.dp, Emerald200)
            ) {
                Text(
                    text = displayValue,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                    color = Emerald700,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        }

        Slider(
            value = value,
            onValueChange = onValueChange,
            valueRange = valueRange,
            steps = steps,
            colors = SliderDefaults.colors(
                thumbColor = Emerald500,
                activeTrackColor = Emerald500,
                inactiveTrackColor = Slate300
            )
        )
    }
}