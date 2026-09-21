package com.velometrics.app.data.cache

import com.velometrics.app.domain.service.RouteRecap
import com.velometrics.app.domain.service.SessionNarrative
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * In-memory last-known Session Detail recaps (tag #214, Repeated Route #217), keyed by session id.
 * Session Detail seeds its recap StateFlows from here so a re-opened ride shows its recap on the
 * first frame instead of popping in once the tag-history query / routes cache resolve; the live
 * flows still refresh (and overwrite) the entry. [RecapWarmer] pre-fills it at app start.
 *
 * A stored "no recap" is distinct from "never computed", hence [Entry].
 */
@Singleton
class SessionRecapCache @Inject constructor() {
    class Entry<T>(val value: T?)

    private val narratives = ConcurrentHashMap<Long, Entry<SessionNarrative>>()
    private val routeRecaps = ConcurrentHashMap<Long, Entry<RouteRecap>>()

    fun narrative(sessionId: Long): SessionNarrative? = narratives[sessionId]?.value
    fun routeRecap(sessionId: Long): RouteRecap? = routeRecaps[sessionId]?.value

    fun putNarrative(sessionId: Long, narrative: SessionNarrative?) {
        narratives[sessionId] = Entry(narrative)
    }

    fun putRouteRecap(sessionId: Long, recap: RouteRecap?) {
        routeRecaps[sessionId] = Entry(recap)
    }

    fun hasNarrative(sessionId: Long): Boolean = narratives.containsKey(sessionId)
}
