package io.github.mangi.eta.agent.accessibility

/** Коли основний потік запиту закінчився, UNKNOWN не можна вважати вікном зниклим.*/
internal enum class PackageWindowVisibility {
    VISIBLE,
    GONE,
    UNKNOWN,
}
