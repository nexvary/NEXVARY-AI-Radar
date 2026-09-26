package com.nexvary.airadar

data class RadarProject(
    val id: String,
    val name: String,
    val description: String,
    val url: String,
    val source: String,
    val category: String,
    val stars: Int = 0,
    val publishedAt: String = "",
    val license: String = "Unknown",
    val score: Int = 0,
    val isLocalFriendly: Boolean = false
)
