package com.google.ai.sample.util

import android.os.Parcelable
import kotlinx.parcelize.Parcelize
import kotlinx.serialization.Serializable

@Parcelize
@Serializable
data class SystemMessageEntry(
    val title: String,
    val guide: String
) : Parcelable
