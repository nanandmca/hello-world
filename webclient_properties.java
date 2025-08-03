@ConfigurationProperties(prefix = "customer-master-adapter.webclients")
@Data
@Component
public class WebClientConfigurationProperties {
    
    private OAuthConfig defaultOauth = new OAuthConfig();
    private ConnectionConfig defaultConnection = new ConnectionConfig();
    private Map<String, CountryConfig> countries = new HashMap<>();
    private Map<String, EnvironmentConfig> environments = new HashMap<>();
    
    @Data
    public static class CountryConfig {
        private Map<String, SubsystemConfig> subsystems = new HashMap<>();
    }
    
    @Data
    public static class SubsystemConfig {
        private String baseUrl;
        private OAuthConfig oauth;
        private ConnectionConfig connection;
        private Map<String, String> subsystemMappings = new HashMap<>();
    }
    
    @Data
    public static class OAuthConfig {
        private String clientId;
        private String clientSecret;
        private String tokenUri;
        private String scope = "read write";
        private Duration tokenCacheDuration = Duration.ofMinutes(55);
    }
    
    @Data
    public static class ConnectionConfig {
        private int timeout = 30000;
        private int readTimeout = 60000;
        private int maxConnections = 100;
        private int maxConnectionsPerRoute = 20;
    }
    
    @Data
    public static class EnvironmentConfig {
        private OAuthConfig oauth;
        private ConnectionConfig connection;
    }
    
    /**
     * Resolves the effective configuration for a given country and subsystem
     */
    public ResolvedConfig resolveConfig(String country, String subsystem, String environment) {
        CountryConfig countryConfig = countries.get(country);
        if (countryConfig == null) {
            throw new IllegalArgumentException("No configuration found for country: " + country);
        }
        
        // Find the actual subsystem configuration (could be mapped to unified)
        SubsystemConfig subsystemConfig = findSubsystemConfig(countryConfig, subsystem);
        if (subsystemConfig == null) {
            throw new IllegalArgumentException(
                String.format("No configuration found for subsystem: %s in country: %s", subsystem, country));
        }
        
        // Merge configurations in order: default -> environment -> country/subsystem
        OAuthConfig effectiveOAuth = mergeOAuthConfig(defaultOauth, environment, subsystemConfig.getOauth());
        ConnectionConfig effectiveConnection = mergeConnectionConfig(defaultConnection, environment, subsystemConfig.getConnection());
        
        String effectiveBaseUrl = subsystemConfig.getBaseUrl();
        String pathMapping = subsystemConfig.getSubsystemMappings().get(subsystem);
        
        return ResolvedConfig.builder()
            .baseUrl(effectiveBaseUrl)
            .pathMapping(pathMapping)
            .oauth(effectiveOAuth)
            .connection(effectiveConnection)
            .cacheKey(generateCacheKey(country, subsystem, effectiveOAuth))
            .build();
    }
    
    private SubsystemConfig findSubsystemConfig(CountryConfig countryConfig, String subsystem) {
        // First check if subsystem exists directly
        if (countryConfig.getSubsystems().containsKey(subsystem)) {
            return countryConfig.getSubsystems().get(subsystem);
        }
        
        // Check if subsystem is mapped to another subsystem config
        for (SubsystemConfig config : countryConfig.getSubsystems().values()) {
            if (config.getSubsystemMappings().containsKey(subsystem)) {
                return config;
            }
        }
        
        return null;
    }
    
    private String generateCacheKey(String country, String subsystem, OAuthConfig oauth) {
        return String.format("%s_%s_%s", country, subsystem, 
            Objects.hash(oauth.getClientId(), oauth.getTokenUri()));
    }
    
    // Merge methods for OAuth and Connection configs...
    private OAuthConfig mergeOAuthConfig(OAuthConfig defaultConfig, String environment, OAuthConfig subsystemConfig) {
        OAuthConfig result = new OAuthConfig();
        
        // Start with defaults
        BeanUtils.copyProperties(defaultConfig, result);
        
        // Apply environment overrides
        EnvironmentConfig envConfig = environments.get(environment);
        if (envConfig != null && envConfig.getOauth() != null) {
            mergeNonNullProperties(envConfig.getOauth(), result);
        }
        
        // Apply subsystem-specific overrides
        if (subsystemConfig != null) {
            mergeNonNullProperties(subsystemConfig, result);
        }
        
        return result;
    }
    
    private void mergeNonNullProperties(Object source, Object target) {
        // Implementation to copy only non-null properties
        // Use reflection or utility libraries like Apache Commons BeanUtils
    }
}

@Data
@Builder
public class ResolvedConfig {
    private String baseUrl;
    private String pathMapping;
    private OAuthConfig oauth;
    private ConnectionConfig connection;
    private String cacheKey;
    
    public String getEffectiveUrl(String path) {
        if (pathMapping != null) {
            return baseUrl + pathMapping + path;
        }
        return baseUrl + path;
    }
}