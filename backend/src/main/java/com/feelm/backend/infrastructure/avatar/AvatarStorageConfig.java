package com.feelm.backend.infrastructure.avatar;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(AvatarStorageProperties.class)
class AvatarStorageConfig {}
