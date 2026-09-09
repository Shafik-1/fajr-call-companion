package com.fajr.callcompanion.model

data class ContactItem(
    val id: String,
    val name: String,
    val phoneNumber: String,
    var isSelected: Boolean = false
)

data class AppSettings(
    val ringDurationSeconds: Int = 25,
    val delayBetweenCallsSeconds: Int = 5
)
