package com.zhousl.aether.data

class PresencePushManager(
    private val repository: PresencePushRepository,
    private val scheduler: PresencePushScheduler,
) {
    val settings = repository.settings

    suspend fun snapshot(): PresencePushSettings = repository.snapshot()

    suspend fun save(settings: PresencePushSettings) {
        repository.save(settings)
        reschedule(settings)
    }

    suspend fun update(transform: (PresencePushSettings) -> PresencePushSettings): PresencePushSettings {
        val updated = repository.update(transform)
        reschedule(updated)
        return updated
    }

    suspend fun markTriggeredAndScheduleNext(
        triggeredAtMillis: Long = System.currentTimeMillis(),
    ): PresencePushSettings = update { settings ->
        settings.copy(lastTriggerAtMillis = triggeredAtMillis)
    }

    suspend fun rescheduleAll() {
        reschedule(repository.snapshot())
    }

    private suspend fun reschedule(settings: PresencePushSettings) {
        if (!settings.enabled) {
            scheduler.cancel()
            return
        }
        val next = settings.nextTriggerAfter()
        if (next == null) {
            scheduler.cancel()
        } else {
            scheduler.schedule(next)
        }
    }
}
