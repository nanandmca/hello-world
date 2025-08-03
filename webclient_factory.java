@Component
@Slf4j
public class CustomerMasterWebClientFactory {
    
    private final WebClientConfigurationProperties config;
    private final Map<String, WebClient> webClientCache = new ConcurrentHashMap<>();
    private final Map<String, OAuth2AccessToken> tokenCache = new ConcurrentHashMap<>();
    private final WebClient oauthClient;
    
    @Value("${customer-master-adapter.country:US}")
    private String defaultCountry;
    
    @Value("${spring.profiles.active:dev}")
    private String environment;
    
    public CustomerMasterWebClientFactory(WebClientConfigurationProperties config) {
        this.config = config;
        this.oauthClient = WebClient.builder()
            .codecs(configurer -> configurer.defaultCodecs().maxInMemorySize(1024 * 1024))
            .build();
    }
    
    /**
     * Get WebClient for specific subsystem and country
     */
    public WebClient getWebClient(String subsystem, String country) {
        country = country != null ? country : defaultCountry;
        ResolvedConfig resolvedConfig = config.resolveConfig(country, subsystem, environment);
        
        return webClientCache.computeIfAbsent(resolvedConfig.getCacheKey(), 
            key -> createWebClient(resolvedConfig));
    }
    
    /**
     * Get WebClient for subsystem using default country
     */
    public WebClient getWebClient(String subsystem) {
        return getWebClient(subsystem, defaultCountry);
    }
    
    /**
     * Create configured WebClient with OAuth filter
     */
    private WebClient createWebClient(ResolvedConfig config) {
        return WebClient.builder()
            .baseUrl(config.getBaseUrl())
            .filter(createOAuthFilter(config.getOauth(), config.getCacheKey()))
            .filter(createLoggingFilter())
            .filter(createRetryFilter())
            .clientConnector(createConnector(config.getConnection()))
            .codecs(configurer -> {
                configurer.defaultCodecs().maxInMemorySize(1024 * 1024);
                configurer.defaultCodecs().enableLoggingRequestDetails(true);
            })
            .build();
    }
    
    /**
     * Create OAuth2 filter with token caching
     */
    private ExchangeFilterFunction createOAuthFilter(OAuthConfig oauthConfig, String cacheKey) {
        return ExchangeFilterFunction.ofRequestProcessor(request -> {
            return getAccessToken(oauthConfig, cacheKey)
                .map(token -> ClientRequest.from(request)
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + token.getTokenValue())
                    .build())
                .doOnError(error -> log.error("Failed to obtain OAuth token for key: {}", cacheKey, error));
        });
    }
    
    /**
     * Get access token with caching and refresh logic
     */
    private Mono<OAuth2AccessToken> getAccessToken(OAuthConfig oauthConfig, String cacheKey) {
        OAuth2AccessToken cachedToken = tokenCache.get(cacheKey);
        
        // Check if token is valid and not expired
        if (cachedToken != null && isTokenValid(cachedToken)) {
            return Mono.just(cachedToken);
        }
        
        // Fetch new token
        return fetchNewToken(oauthConfig)
            .doOnNext(token -> {
                tokenCache.put(cacheKey, token);
                log.debug("Cached OAuth token for key: {}", cacheKey);
            })
            .doOnError(error -> {
                log.error("Failed to fetch OAuth token for config: {}", oauthConfig.getClientId(), error);
                // Remove invalid cached token
                tokenCache.remove(cacheKey);
            });
    }
    
    /**
     * Fetch new OAuth token using client credentials flow
     */
    private Mono<OAuth2AccessToken> fetchNewToken(OAuthConfig oauthConfig) {
        MultiValueMap<String, String> formData = new LinkedMultiValueMap<>();
        formData.add("grant_type", "client_credentials");
        formData.add("client_id", oauthConfig.getClientId());
        formData.add("client_secret", oauthConfig.getClientSecret());
        formData.add("scope", oauthConfig.getScope());
        
        return oauthClient
            .post()
            .uri(oauthConfig.getTokenUri())
            .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_FORM_URLENCODED_VALUE)
            .body(BodyInserters.fromFormData(formData))
            .retrieve()
            .onStatus(HttpStatusCode::isError, response -> 
                response.bodyToMono(String.class)
                    .flatMap(body -> Mono.error(new OAuth2AuthenticationException(
                        "Failed to obtain access token: " + body))))
            .bodyToMono(OAuth2TokenResponse.class)
            .map(this::convertToAccessToken);
    }
    
    private OAuth2AccessToken convertToAccessToken(OAuth2TokenResponse response) {
        Instant issuedAt = Instant.now();
        Instant expiresAt = issuedAt.plusSeconds(response.getExpiresIn());
        
        return new OAuth2AccessToken(
            OAuth2AccessToken.TokenType.BEARER,
            response.getAccessToken(),
            issuedAt,
            expiresAt,
            StringUtils.hasText(response.getScope()) ? 
                Set.of(response.getScope().split("\\s+")) : null
        );
    }
    
    private boolean isTokenValid(OAuth2AccessToken token) {
        if (token.getExpiresAt() == null) {
            return true; // Non-expiring token
        }
        
        // Add 5-minute buffer before expiration
        Instant expirationWithBuffer = token.getExpiresAt().minus(5, ChronoUnit.MINUTES);
        return Instant.now().isBefore(expirationWithBuffer);
    }
    
    /**
     * Create HTTP client connector with connection pooling
     */
    private ClientHttpConnector createConnector(ConnectionConfig connectionConfig) {
        ConnectionProvider connectionProvider = ConnectionProvider.builder("custom")
            .maxConnections(connectionConfig.getMaxConnections())
            .maxIdleTime(Duration.ofSeconds(60))
            .maxLifeTime(Duration.ofMinutes(10))
            .pendingAcquireTimeout(Duration.ofSeconds(30))
            .evictInBackground(Duration.ofSeconds(120))
            .build();
        
        HttpClient httpClient = HttpClient.create(connectionProvider)
            .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, connectionConfig.getTimeout())
            .responseTimeout(Duration.ofMillis(connectionConfig.getReadTimeout()))
            .doOnConnected(conn -> {
                conn.addHandlerLast(new ReadTimeoutHandler(connectionConfig.getReadTimeout(), TimeUnit.MILLISECONDS));
                conn.addHandlerLast(new WriteTimeoutHandler(connectionConfig.getTimeout(), TimeUnit.MILLISECONDS));
            });
        
        return new ReactorClientHttpConnector(httpClient);
    }
    
    /**
     * Logging filter for debugging
     */
    private ExchangeFilterFunction createLoggingFilter() {
        return ExchangeFilterFunction.ofRequestProcessor(request -> {
            log.debug("Request: {} {}", request.method(), request.url());
            return Mono.just(request);
        });
    }
    
    /**
     * Retry filter for transient failures
     */
    private ExchangeFilterFunction createRetryFilter() {
        return ExchangeFilterFunction.ofResponseProcessor(response -> {
            if (response.statusCode().is5xxServerError()) {
                return Mono.error(new WebClientResponseException(
                    "Server error", response.statusCode().value(), 
                    response.statusCode().getReasonPhrase(), null, null, null));
            }
            return Mono.just(response);
        });
    }
    
    /**
     * Clear token cache (useful for testing or manual refresh)
     */
    public void clearTokenCache() {
        tokenCache.clear();
        log.info("OAuth token cache cleared");
    }
    
    /**
     * Clear specific token from cache
     */
    public void clearTokenCache(String subsystem, String country) {
        String cacheKey = config.resolveConfig(country != null ? country : defaultCountry, 
            subsystem, environment).getCacheKey();
        tokenCache.remove(cacheKey);
        log.info("OAuth token cache cleared for key: {}", cacheKey);
    }
}

// Supporting classes
@Data
public class OAuth2TokenResponse {
    @JsonProperty("access_token")
    private String accessToken;
    
    @JsonProperty("token_type")
    private String tokenType;
    
    @JsonProperty("expires_in")
    private Long expiresIn;
    
    @JsonProperty("scope")
    private String scope;
}