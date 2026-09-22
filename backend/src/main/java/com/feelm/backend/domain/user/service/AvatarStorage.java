package com.feelm.backend.domain.user.service;

public interface AvatarStorage {

    String store(byte[] source);

    void delete(String storageKey);

    String publicUrl(String storageKey);
}
