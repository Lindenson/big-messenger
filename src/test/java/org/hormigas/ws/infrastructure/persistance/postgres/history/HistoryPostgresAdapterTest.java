package org.hormigas.ws.infrastructure.persistance.postgres.history;

import io.smallrye.mutiny.Uni;
import org.hormigas.ws.domain.message.Message;
import org.hormigas.ws.infrastructure.persistance.postgres.dto.HistoryRow;
import org.hormigas.ws.infrastructure.persistance.postgres.mappers.MessageMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.time.Instant;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class HistoryPostgresAdapterTest {

    @InjectMocks
    HistoryPostgresAdapter adapter;

    @Mock
    HistoryPostgresRepository repository;

    @Mock
    MessageMapper mapper;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
    }

    // ------------------- addBySenderId -------------------
    @Test
    void addBySenderId_nullClientOrMessage_skipsInsert() {
        adapter.addBySenderId(null, null);
        adapter.addBySenderId("cid", null);
        adapter.addBySenderId(null, Message.builder().messageId("m1").payload(new Message.Payload()).build());

        verifyNoInteractions(repository);
        verifyNoInteractions(mapper);
    }

    @Test
    void addBySenderId_invalidHistoryRow_skipsInsert() {
        Message msg = Message.builder().messageId("m1").payload(new Message.Payload()).build();
        HistoryRow row = new HistoryRow(null, "c1", "s1", "r1", null, "{}", Instant.now());
        when(mapper.toHistoryRow(msg)).thenReturn(row);

        adapter.addBySenderId("cid", msg);

        verify(repository, never()).insertHistoryBatch(any());
    }

    @Test
    void addBySenderId_validMessage_callsRepository() {
        Message msg = Message.builder().messageId("m1").payload(new Message.Payload()).build();
        HistoryRow row = new HistoryRow("m1", "c1", "s1", "r1", null, "{}", Instant.now());
        when(mapper.toHistoryRow(msg)).thenReturn(row);
        when(repository.insertHistoryBatch(List.of(row))).thenReturn(Uni.createFrom().item(List.of()));

        adapter.addBySenderId("cid", msg);

        verify(repository).insertHistoryBatch(List.of(row));
    }

    @Test
    void addBySenderId_repositoryFailure_loggedButNotThrown() {
        Message msg = Message.builder().messageId("m1").payload(new Message.Payload()).build();
        HistoryRow row = new HistoryRow("m1", "c1", "s1", "r1", null, "{}", Instant.now());
        when(mapper.toHistoryRow(msg)).thenReturn(row);
        when(repository.insertHistoryBatch(List.of(row))).thenReturn(Uni.createFrom().failure(new RuntimeException("fail")));

        adapter.addBySenderId("cid", msg);

        verify(repository).insertHistoryBatch(List.of(row));
    }
}
