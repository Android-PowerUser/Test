package com.google.ai.sample.util

import kotlinx.serialization.Serializable

@Serializable
data class SystemMessageEntry(
    val title: String,
    val guide: String
)
