package dev.ajkneisl.health.monitor

fun interface StatusChangeListener {
    suspend fun onStatusChange(endpoint: EndpointSnapshot, previous: Status)

    /** Called after every check, following [onStatusChange] when the status changed. */
    suspend fun onCheck(endpoint: EndpointSnapshot) {}
}
