@Service
@Slf4j
public class CustomerMasterService {
    
    private final CustomerMasterWebClientFactory webClientFactory;
    private final WebClientConfigurationProperties config;
    
    @Value("${customer-master-adapter.country:US}")
    private String defaultCountry;
    
    @Value("${spring.profiles.active:dev}")
    private String environment;
    
    public CustomerMasterService(CustomerMasterWebClientFactory webClientFactory,
                               WebClientConfigurationProperties config) {
        this.webClientFactory = webClientFactory;
        this.config = config;
    }
    
    // Product Service Methods
    public Mono<ProductResponse> getProduct(String productId, String country) {
        return makeRequest("product", country, "/products/" + productId, ProductResponse.class);
    }
    
    public Mono<ProductResponse> getProduct(String productId) {
        return getProduct(productId, defaultCountry);
    }
    
    public Flux<ProductResponse> searchProducts(ProductSearchRequest request, String country) {
        return makeRequestFlux("product", country, "/products/search", request, ProductResponse.class);
    }
    
    // Profile Service Methods
    public Mono<ProfileResponse> getProfile(String customerId, String country) {
        return makeRequest("profile", country, "/profiles/" + customerId, ProfileResponse.class);
    }
    
    public Mono<ProfileResponse> updateProfile(String customerId, ProfileUpdateRequest request, String country) {
        return makeRequest("profile", country, "/profiles/" + customerId, request, ProfileResponse.class, HttpMethod.PUT);
    }
    
    // Preferences Service Methods
    public Mono<PreferencesResponse> getPreferences(String customerId, String country) {
        return makeRequest("preferences", country, "/preferences/" + customerId, PreferencesResponse.class);
    }
    
    public Mono<PreferencesResponse> updatePreferences(String customerId, PreferencesUpdateRequest request, String country) {
        return makeRequest("preferences", country, "/preferences/" + customerId, request, PreferencesResponse.class, HttpMethod.PUT);
    }
    
    // E-Services Methods
    public Mono<EServiceResponse> getEServices(String customerId, String country) {
        return makeRequest("eservices", country, "/eservices/" + customerId, EServiceResponse.class);
    }
    
    public Mono<EServiceResponse> subscribeToService(String customerId, EServiceSubscriptionRequest request, String country) {
        return makeRequest("eservices", country, "/eservices/" + customerId + "/subscribe", request, EServiceResponse.class, HttpMethod.POST);
    }
    
    // Generic request methods
    private <T> Mono<T> makeRequest(String subsystem, String country, String path, Class<T> responseType) {
        return makeRequest(subsystem, country, path, null, responseType, HttpMethod.GET);
    }
    
    private <T, R> Mono<T> makeRequest(String subsystem, String country, String path, R requestBody, 
                                      Class<T> responseType, HttpMethod method) {
        return Mono.fromSupplier(() -> {
            ResolvedConfig resolvedConfig = config.resolveConfig(
                country != null ? country : defaultCountry, subsystem, environment);
            WebClient webClient = webClientFactory.getWebClient(subsystem, country);
            
            String effectiveUrl = buildEffectiveUrl(resolvedConfig, path);
            
            WebClient.RequestBodySpec requestSpec = webClient
                .method(method)
                .uri(effectiveUrl);
            
            if (requestBody != null) {
                requestSpec = requestSpec.bodyValue(requestBody);
            }
            
            return requestSpec
                .retrieve()
                .onStatus(HttpStatusCode::isError, this::handleErrorResponse)
                .bodyToMono(responseType)
                .doOnNext(response -> log.debug("Received response from {}: {}", effectiveUrl, response))
                .doOnError(error -> log.error("Error calling {}: {}", effectiveUrl, error.getMessage()));
        }).flatMap(mono -> mono);
    }
    
    private <T, R> Flux<T> makeRequestFlux(String subsystem, String country, String path, R requestBody, Class<T> responseType) {
        return Flux.defer(() -> {
            ResolvedConfig resolvedConfig = config.resolveConfig(
                country != null ? country : defaultCountry, subsystem, environment);
            WebClient webClient = webClientFactory.getWebClient(subsystem, country);
            
            String effectiveUrl = buildEffectiveUrl(resolvedConfig, path);
            
            WebClient.RequestBodySpec requestSpec = webClient
                .post()
                .uri(effectiveUrl);
            
            if (requestBody != null) {
                requestSpec = requestSpec.bodyValue(requestBody);
            }
            
            return requestSpec
                .retrieve()
                .onStatus(HttpStatusCode::isError, this::handleErrorResponse)
                .bodyToFlux(responseType);
        });
    }
    
    private String buildEffectiveUrl(ResolvedConfig config, String path) {
        if (config.getPathMapping() != null) {
            return config.getPathMapping() + path;
        }
        return path;
    }
    
    private Mono<? extends Throwable> handleErrorResponse(ClientResponse response) {
        return response.bodyToMono(String.class)
            .flatMap(body -> {
                log.error("HTTP {} error: {}", response.statusCode(), body);
                return Mono.error(new CustomerMasterException(
                    "HTTP " + response.statusCode() + ": " + body,
                    response.statusCode().value()
                ));
            });
    }
    
    // Health check methods for each subsystem
    public Mono<Boolean> isSubsystemHealthy(String subsystem, String country) {
        return makeRequest(subsystem, country, "/health", String.class)
            .map(response -> "OK".equals(response))
            .onErrorReturn(false);
    }
    
    public Mono<Map<String, Boolean>> checkAllSubsystemsHealth(String country) {
        List<String> subsystems = Arrays.asList("product", "profile", "preferences", "eservices");
        
        return Flux.fromIterable(subsystems)
            .flatMap(subsystem -> 
                isSubsystemHealthy(subsystem, country)
                    .map(healthy -> Map.entry(subsystem, healthy))
            )
            .collectMap(Map.Entry::getKey, Map.Entry::getValue);
    }
}

// Exception class
public class CustomerMasterException extends RuntimeException {
    private final int statusCode;
    
    public CustomerMasterException(String message, int statusCode) {
        super(message);
        this.statusCode = statusCode;
    }
    
    public int getStatusCode() {
        return statusCode;
    }
}

// Sample request/response classes
@Data
public class ProductSearchRequest {
    private String category;
    private String brand;
    private String priceRange;
    private int page = 0;
    private int size = 20;
}

@Data
public class ProfileUpdateRequest {
    private String firstName;
    private String lastName;
    private String email;
    private String phone;
    private Map<String, Object> customFields;
}

@Data
public class PreferencesUpdateRequest {
    private Map<String, String> preferences;
    private List<String> categories;
    private String language;
    private String currency;
}

@Data
public class EServiceSubscriptionRequest {
    private String serviceId;
    private String planId;
    private Map<String, Object> parameters;
}

// Sample response classes
@Data
public class ProductResponse {
    private String id;
    private String name;
    private String description;
    private BigDecimal price;
    private String category;
    private String brand;
}

@Data
public class ProfileResponse {
    private String customerId;
    private String firstName;
    private String lastName;
    private String email;
    private String phone;
    private String country;
    private Map<String, Object> customFields;
}

@Data
public class PreferencesResponse {
    private String customerId;
    private Map<String, String> preferences;
    private List<String> categories;
    private String language;
    private String currency;
    private LocalDateTime lastUpdated;
}

@Data
public class EServiceResponse {
    private String customerId;
    private List<ServiceSubscription> subscriptions;
    private List<ServiceOffering> availableServices;
    
    @Data
    public static class ServiceSubscription {
        private String serviceId;
        private String planId;
        private String status;
        private LocalDateTime subscribedDate;
        private LocalDateTime expiryDate;
    }
    
    @Data
    public static class ServiceOffering {
        private String serviceId;
        private String name;
        private String description;
        private List<ServicePlan> plans;
    }
    
    @Data
    public static class ServicePlan {
        private String planId;
        private String name;
        private BigDecimal price;
        private String billingCycle;
    }
}