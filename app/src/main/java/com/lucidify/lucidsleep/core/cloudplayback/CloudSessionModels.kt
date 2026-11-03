package com.lucidify.lucidsleep.core.cloudplayback

data class ChunkMeta(
    val seq: Int,
    val t0Ms: Long,
    val t1Ms: Long,
    val bytes: Long? = null,
    val sha256: String? = null,
    val getUrl: String? = null
)

data class SessionManifest(
    val sessionId: String,
    val sampleRateHz: Int,
    val channels: List<String>,
    val finalized: Boolean,
    val chunks: List<ChunkMeta>
)