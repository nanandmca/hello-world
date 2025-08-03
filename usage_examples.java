// Controller example showing usage
@RestController
@RequestMapping("/api/customer")
@Slf4j
public class CustomerController {
    
    private final CustomerMasterService customerService;
    
    public CustomerController(CustomerMasterService customerService) {
        this.customerService = customerService;
    }
    
    @GetMapping("/{customerId}/profile")
    public Mono<ResponseEntity<ProfileResponse>> getProfile(
            @PathVariable String customerId,
            @RequestHeader(value = "X-Country", required = false) String country) {
        
        return customerService.getProfile(customerId, country)
            .map(ResponseEntity::ok)
            .onErrorReturn(ResponseEntity.notFound().build());
    }
    
    @GetMapping("/{customerId}/products")
    public Flux<ProductResponse> searchProducts(
            @PathVariable String customerId,
            @RequestParam(required = false) String category,
            @RequestParam(required = false) String brand,
            @RequestHeader(value = "X-Country", required = false) String country) {
        
        ProductSearchRequest request = new ProductSearchRequest();
        request.setCategory(category);
        request.setBrand(brand);
        
        return customerService.searchProducts(request, country);
    }
    
    @PutMapping("/{customerId}/preferences")
    public Mono<ResponseEntity<PreferencesResponse>> updatePreferences(
            @PathVariable String customerId,
            @RequestBody PreferencesUpdateRequest request,
            @RequestHeader(value = "X-Country", required = false) String country) {
        
        return customerService.updatePreferences(customerId, request, country)
            .map(ResponseEntity::ok)
            .onErrorReturn(ResponseEntity.badRequest().build());
    }
    
    @GetMapping("/health/{country}")
    public Mono<Map<String, Boolean>> getHealthStatus(@PathVariable String country) {
        return customerService.checkAllSubsystemsHealth(country);
    }
}

// Configuration for different environments
@Configuration
@Profile("dev")
public class DevConfig {
    
    @Bean
    @ConfigurationProperties("customer-master-adapter.webclients.dev")
    public WebClientConfigurationProperties devWebClientConfig() {
        return new WebClientConfigurationProperties();
    }
}

@Configuration
@Profile("prod")
public class ProdConfig {
    
    @Bean
    @ConfigurationProperties("customer-master-adapter.webclients.prod")
    public WebClientConfigurationProperties prodWebClientConfig() {
        return new WebClientConfigurationProperties();
    }
}

// Testing utilities
@TestConfiguration
public class TestWebClientConfig {
    
    @Bean
    @Primary
    public CustomerMasterWebClientFactory testWebClientFactory() {
        // Mock or stub implementation for testing
        return Mockito.mock(CustomerMasterWebClientFactory.class);
    }
}

// Example of manual WebClient usage for advanced scenarios
@Component
public class AdvancedCustomerService {
    
    private final CustomerMasterWebClientFactory webClientFactory;
    
    public AdvancedCustomerService(CustomerMasterWebClientFactory webClientFactory) {
        this.webClientFactory = webClientFactory;
    }
    
    // Manual WebClient usage for complex scenarios
    public Mono<String> performComplexOperation(String customerId, String country) {
        WebClient productClient = webClientFactory.getWebClient("product", country);
        WebClient profileClient = webClientFactory