package com.tanzu.creditengine.config;

import com.tanzu.creditengine.entity.CreditScoreCache;
import org.apache.geode.cache.client.ClientRegionShortcut;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.gemfire.config.annotation.EnableEntityDefinedRegions;
import org.springframework.data.gemfire.repository.config.EnableGemfireRepositories;

/**
 * GemFire configuration for the CreditScoreCache region.
 */
@Configuration
@EnableGemfireRepositories(basePackages = "com.tanzu.creditengine.repository")
@EnableEntityDefinedRegions(basePackageClasses = CreditScoreCache.class, clientRegionShortcut = ClientRegionShortcut.PROXY)
@org.springframework.data.gemfire.config.annotation.EnableCachingDefinedRegions
public class GemFireConfig {

    // Manual bean definition removed to rely on @EnableEntityDefinedRegions
    // and avoid BeanDefinitionOverrideException
}
