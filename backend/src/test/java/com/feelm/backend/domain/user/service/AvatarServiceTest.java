package com.feelm.backend.domain.user.service;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.feelm.backend.global.error.BusinessException;
import com.feelm.backend.global.error.ErrorCode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class AvatarServiceTest {

    @Mock private AvatarStorage storage;
    @Mock private AvatarAccountWriter writer;

    private AvatarService service;

    @BeforeEach
    void setUp() {
        service = new AvatarService(storage, writer);
    }

    @Test
    void rejectsInactiveAccountBeforeImageOrStorageWork() {
        BusinessException unauthorized = new BusinessException(ErrorCode.UNAUTHORIZED);
        org.mockito.Mockito.doThrow(unauthorized).when(writer).requireActive(7L);

        assertThatThrownBy(() -> service.upload(7L, new byte[] {1})).isSameAs(unauthorized);

        verify(storage, never()).store(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void removesNewObjectWhenLockedDatabaseReplacementFails() {
        byte[] source = new byte[] {1};
        when(storage.store(source)).thenReturn("new.jpg");
        BusinessException unauthorized = new BusinessException(ErrorCode.UNAUTHORIZED);
        when(writer.replace(7L, "new.jpg")).thenThrow(unauthorized);

        assertThatThrownBy(() -> service.upload(7L, source)).isSameAs(unauthorized);

        InOrder order = inOrder(writer, storage);
        order.verify(writer).requireActive(7L);
        order.verify(storage).store(source);
        order.verify(writer).replace(7L, "new.jpg");
        order.verify(storage).delete("new.jpg");
    }

    @Test
    void commitsNewKeyBeforeRemovingPreviousObject() {
        byte[] source = new byte[] {1};
        when(storage.store(source)).thenReturn("new.jpg");
        when(writer.replace(7L, "new.jpg")).thenReturn("old.jpg");
        when(storage.publicUrl("new.jpg")).thenReturn("/media/avatars/new.jpg");

        service.upload(7L, source);

        InOrder order = inOrder(writer, storage);
        order.verify(writer).requireActive(7L);
        order.verify(storage).store(source);
        order.verify(writer).replace(7L, "new.jpg");
        order.verify(storage).delete("old.jpg");
        order.verify(storage).publicUrl("new.jpg");
    }
}
