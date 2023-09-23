package info.nightscout.rx.bus

import info.nightscout.rx.events.Event
import io.reactivex.rxjava3.core.Observable

interface RxBus {

    fun send(event: Event)

    // Listen should return an Observable and not the publisher
    // Using ofType we filter only events that match that class type
    fun <T : Any> toObservable(eventType: Class<T>): Observable<T>
}