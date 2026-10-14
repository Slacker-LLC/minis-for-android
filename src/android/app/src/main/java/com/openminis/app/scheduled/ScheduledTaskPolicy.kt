package com.openminis.app.scheduled

/** Pure policy helpers shared by scheduled-task surfaces and JVM tests. */
object ScheduledTaskPolicy {
    /**
     * Why an ENABLED [task] could never fire, or null when it has an upcoming run. Saving such a task
     * "succeeded" and then nothing ever happened: no weekday chosen for a custom repeat, an end date
     * before the start, or an end date already past.
     */
    fun schedulingProblem(task: ScheduledTask, now: Long = System.currentTimeMillis()): String? {
        if (!task.enabled) return null
        if (task.repeatMode == ScheduledRepeatMode.CUSTOM && task.customDays.isEmpty()) {
            return "Choose at least one weekday for a custom repeat."
        }
        val start = task.startDateMs
        val end = task.endDateMs
        if (start != null && end != null && end < start) {
            return "The end date is before the start date."
        }
        if (task.nextTriggerMs(now) == null) {
            return "This routine has no upcoming run (its end date has passed)."
        }
        return null
    }

}
