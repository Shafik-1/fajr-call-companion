package com.fajr.callcompanion.model

data class Contact(
    val id: String,
    val name: String,
    val phoneNumber: String,
    val ringDurationSeconds: Int = 25
)
