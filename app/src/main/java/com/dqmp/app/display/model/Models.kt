package com.dqmp.app.display.model

data class QueueItem(
    val id: String,
    val tokenNumber: String,
    val status: String,
    val counterId: String?,
    val counterName: String?,
    val createdAt: String
)

data class TokenCall(
    val tokenNumber: String,
    val counterId: String,
    val counterName: String,
    val timestamp: String,
    val customerName: String = "",
    val language: String = "en"
)

data class DisplaySettings(
    val language: String = "en",
    val volume: Float = 1.0f,
    val enableAnnouncements: Boolean = true,
    val theme: String = "emerald",
    val showQueueNumbers: Boolean = true,
    val autoScrollSpeed: Int = 3000,
    val outletId: String = "",
    val serverUrl: String = "",
    val isConfigured: Boolean = false,
    val configurationId: String = "",
    val outletName: String = ""
)

data class ConfigurationData(
    val outletId: String,
    val outletName: String,
    val serverUrl: String,
    val configurationId: String,
    val theme: String = "emerald",
    val language: String = "en"
)

data class QRCodeData(
    val deviceId: String,
    val deviceName: String,
    val configurationUrl: String
)