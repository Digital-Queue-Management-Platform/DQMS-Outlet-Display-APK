package com.dqmp.app.display.data

import kotlinx.serialization.Serializable

@Serializable
data class DisplayData(
    val waiting: List<Token> = emptyList(),
    val inService: List<Token> = emptyList(),
    val availableOfficers: Int = 0,
    val totalWaiting: Int = 0,
    val recentlyCalled: List<CalledRecord> = emptyList(),
    val outletMeta: OutletMeta? = null,
    val displaySettings: DisplaySettings? = null
)

@Serializable
data class Token(
    val id: String,
    val tokenNumber: Int,
    val status: String,
    val counterNumber: Int? = null,
    val serviceTypes: List<String> = emptyList(),
    val customer: Customer? = null
)

@Serializable
data class Customer(
    val name: String? = null,
    val preferredLanguage: String? = null,
    val preferredLanguages: List<String> = emptyList()
)

@Serializable
data class CalledRecord(
    val id: String,
    val tokenNumber: Int,
    val counterNumber: Int? = null,
    val serviceTypes: List<String> = emptyList(),
    val calledAt: String? = null,
    val customer: Customer? = null
)

@Serializable
data class OutletMeta(
    val name: String,
    val location: String = ""
)

@Serializable
data class DisplaySettings(
    val refresh: Int? = 10,
    val next: Int? = 8,
    val services: Boolean? = true,
    val counters: Boolean? = true,
    val recent: Boolean? = true,
    val autoSlide: Boolean? = true,
    val playTone: Boolean? = true,
    val contentScale: Int? = 100
)

@Serializable
data class CounterStatus(
    val number: Int?,
    val isStaffed: Boolean,
    val officer: Officer? = null
)

@Serializable
data class Officer(
    val id: String? = null,
    val name: String,
    val status: String, // "available", "serving", "on_break", "offline"
    val services: List<String> = emptyList()
)

@Serializable
data class BranchStatusResponse(
    val isClosed: Boolean = false,
    val activeNotice: Notice? = null,
    val standardNotice: Notice? = null
)

@Serializable
data class Notice(
    val title: String,
    val message: String = ""
)

// Service type information for displaying badges and categories
@Serializable
data class ServiceTypeInfo(
    val code: String,
    val name: String,
    val category: String? = null,
    val color: String? = null
)

// Enhanced display metrics for monitoring
@Serializable
data class DisplayMetrics(
    val connectionStatus: String = "online", // "online", "offline", "reconnecting"
    val lastFetchTime: Long = 0L,
    val totalUpdates: Int = 0,
    val averageResponseTime: Int = 0
)
