package com.faith.paymentservice;

import com.faith.pay.outbox.*;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class OutboxWorkerTests {
    @Test
    void dispatchesClaimedEventAndMarksItPublished() {
        var repository = mock(OutboxRepository.class);
        var sink = mock(OutboxSink.class);
        var event = new OutboxEvent(19, "PAYMENT", "PAY-19", "PAYMENT_SUCCEEDED", "{}", 1);
        when(repository.claimBatch(25, 120, 8)).thenReturn(List.of(event));
        var worker = new OutboxWorker(repository, sink, 25, 120);

        worker.dispatchBatch();

        verify(sink).dispatch(event);
        verify(repository).markPublished(19, 1);
        verify(repository, never()).markFailed(anyLong(), anyInt(), anyString(), anyLong(), anyInt());
    }

    @Test
    void dispatchFailureSchedulesRetryInsteadOfAffectingFinancialTransaction() {
        var repository = mock(OutboxRepository.class);
        var sink = mock(OutboxSink.class);
        var event = new OutboxEvent(20, "REFUND", "REF-20", "REFUND_SUCCEEDED", "{}", 2);
        when(repository.claimBatch(25, 120, 8)).thenReturn(List.of(event));
        doThrow(new IllegalStateException("downstream unavailable")).when(sink).dispatch(event);
        var worker = new OutboxWorker(repository, sink, 25, 120);

        worker.dispatchBatch();

        verify(repository).markFailed(eq(20L), eq(2), eq("IllegalStateException"), anyLong(), eq(8));
        verify(repository, never()).markPublished(anyLong(), anyInt());
    }
}
