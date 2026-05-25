package com.example.model

import com.squareup.moshi.JsonClass

@JsonClass(generateAdapter = true)
data class DeviceConnection(
    val name: String,
    val ip: String,
    val http_port: Int,
    val udp_port: Int
)
