package com.localfind.core.indexing

/**
 * Default micro-pacing delay in milliseconds inserted between consecutive media chunks
 * to yield GPU and CPU time-slices to Android's SurfaceFlinger and HWUI compositor,
 * preventing UI lag and frame drops while preserving >90% indexing speed.
 */
const val DEFAULT_MICRO_PACING_DELAY_MS = 20L
