package org.hormigas.ws.infrastructure.websocket.filter;

import org.hormigas.ws.domain.session.ClientSession;

public interface ChannelFilter<T, M>{
    boolean filter(T message, ClientSession<M> clientSession);
}
