package com.feelm.backend.domain.user.service;

import com.feelm.backend.domain.user.dto.response.AvatarResponse;
import com.feelm.backend.global.error.BusinessException;
import com.feelm.backend.global.error.ErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class AvatarService {

    public static final int MAX_UPLOAD_BYTES = 10 * 1024 * 1024;

    private final AvatarStorage storage;
    private final AvatarAccountWriter writer;

    public AvatarResponse upload(long userId, byte[] source) {
        // Reject stale tokens before image decoding and disk I/O. replace() checks again under the
        // account lock so a concurrent withdrawal cannot install a new key after this precheck.
        writer.requireActive(userId);
        if (source.length == 0) {
            throw new BusinessException(ErrorCode.AVATAR_INVALID);
        }
        if (source.length > MAX_UPLOAD_BYTES) {
            throw new BusinessException(ErrorCode.AVATAR_TOO_LARGE);
        }
        String stored = storage.store(source);
        String previous;
        try {
            previous = writer.replace(userId, stored);
        } catch (RuntimeException exception) {
            storage.delete(stored);
            throw exception;
        }
        storage.delete(previous);
        return new AvatarResponse(storage.publicUrl(stored));
    }

    public void delete(long userId) {
        storage.delete(writer.remove(userId));
    }
}
