package com.ephora.sl

/**
 * Mobile-world streaming policy derived from Second Life's interest management.
 * Start with a small server "Far" distance and increase it after arrival so the
 * simulator prioritizes objects around the avatar before expanding the scene.
 */
object WorldRenderConfig {
  const val START_FAR_METERS = 64f
  const val TARGET_FAR_METERS = 128f
  const val FAR_STEP_SECONDS = 1000L
  const val FAR_STEP_FRACTION = 0.10f

  const val VISUAL_RADIUS_METERS = 128.0
  const val TEXTURE_IDS_WINDOW = 512
  const val TEXTURE_REQUESTS_PER_KICK = 48
  const val UDP_TEXTURE_ACTIVE = 4
  const val MESH_VISIBLE_WINDOW = 384
  const val OBJECT_PUBLISH_BUDGET = 1800

  @Volatile var serverFarMeters: Float = START_FAR_METERS
    private set
  @Volatile var lastFarStepMs: Long = 0L
    private set

  @Synchronized fun reset(now: Long = System.currentTimeMillis()) {
    serverFarMeters = START_FAR_METERS
    lastFarStepMs = now
  }

  @Synchronized fun advanceFar(now: Long = System.currentTimeMillis()): Float {
    if (serverFarMeters >= TARGET_FAR_METERS) {
      serverFarMeters = TARGET_FAR_METERS
      return serverFarMeters
    }
    if (lastFarStepMs == 0L) lastFarStepMs = now
    if (now - lastFarStepMs >= FAR_STEP_SECONDS) {
      val steps = ((now - lastFarStepMs) / FAR_STEP_SECONDS).coerceAtMost(8L)
      repeat(steps.toInt()) {
        serverFarMeters = minOf(
          TARGET_FAR_METERS,
          serverFarMeters + TARGET_FAR_METERS * FAR_STEP_FRACTION
        )
      }
      lastFarStepMs += steps * FAR_STEP_SECONDS
    }
    return serverFarMeters
  }
}
