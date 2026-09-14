import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

/** Validates checked-in YAML structure and the Payment/Order integration contract. */
public class ValidatePaymentConfiguration {
    private static final List<String> ENVIRONMENTS = List.of("dev", "stage", "prod");
    private static final List<String> WORKER_KEYS = List.of(
            "outbox.poll-delay-ms", "outbox.batch-size", "outbox.max-attempts", "outbox.send-timeout-seconds",
            "expiry.poll-delay-ms", "reconciliation.poll-delay-ms", "webhook.max-body-bytes",
            "webhook.stripe-requests-per-minute", "webhook.max-attempts", "webhook.retry-delay-ms",
            "refunds.lease-seconds", "refunds.max-attempts", "refunds.retry-base-seconds",
            "refunds.batch-size", "refunds.poll-delay-ms", "cancellations.poll-delay-ms");
    private static final Set<String> LOG_LEVELS = Set.of("TRACE", "DEBUG", "INFO", "WARN", "ERROR", "FATAL", "OFF");
    private static final Set<String> ERRORS = new LinkedHashSet<>();

    public static void main(String[] args) throws Exception {
        if (args.length != 1) {
            System.err.println("Usage: java --class-path <snakeyaml.jar> scripts/ValidatePaymentConfiguration.java <config-repository>");
            System.exit(2);
        }
        Path root = Path.of(args[0]).toAbsolutePath().normalize();
        Map<String, Map<String, Object>> configurations = new LinkedHashMap<>();
        LoaderOptions options = new LoaderOptions();
        options.setAllowDuplicateKeys(false);
        options.setAllowRecursiveKeys(false);
        Yaml yaml = new Yaml(new SafeConstructor(options));
        int count = 0;
        for (String environment : ENVIRONMENTS) {
            Path directory = root.resolve(environment);
            if (!Files.isDirectory(directory)) {
                error(environment, "environment directory is missing");
                continue;
            }
            try (Stream<Path> files = Files.walk(directory)) {
                for (Path file : files.filter(Files::isRegularFile).filter(ValidatePaymentConfiguration::isYaml).sorted().toList()) {
                    String name = root.relativize(file).toString().replace('\\', '/');
                    count++;
                    try (InputStream input = Files.newInputStream(file)) {
                        Object document = yaml.load(input);
                        if (!(document instanceof Map<?, ?> mapping)) {
                            error(name, "YAML root must be a mapping");
                            continue;
                        }
                        Map<String, Object> flattened = new LinkedHashMap<>();
                        flatten(name, "", mapping, flattened);
                        configurations.put(name, flattened);
                    } catch (Exception exception) {
                        // Parser messages may contain source lines. Never echo checked-in values.
                        error(name, "invalid YAML or duplicate key (" + exception.getClass().getSimpleName() + ")");
                    }
                }
            }
        }
        for (String environment : ENVIRONMENTS) {
            String paymentFile = environment + "/payment-service-" + environment + ".yml";
            String orderFile = environment + "/order-service-" + environment + ".yml";
            String sharedFile = environment + "/application-" + environment + ".yml";
            validatePayment(paymentFile, requireFile(configurations, paymentFile), environment);
            validateOrder(orderFile, requireFile(configurations, orderFile));
            Map<String, Object> shared = requireFile(configurations, sharedFile);
            if (!environment.equals("dev")) {
                equal(sharedFile, shared, "spring.kafka.properties.security.protocol", "SASL_SSL");
                equal(sharedFile, shared, "spring.kafka.properties.sasl.mechanism", "${KAFKA_SASL_MECHANISM:SCRAM-SHA-512}");
                equal(sharedFile, shared, "spring.kafka.properties.sasl.jaas.config", "${KAFKA_SASL_JAAS_CONFIG}");
            }
        }
        if (!ERRORS.isEmpty()) {
            ERRORS.forEach(System.err::println);
            System.err.println("Configuration validation failed with " + ERRORS.size() + " error(s).");
            System.exit(1);
        }
        System.out.println("Validated " + count + " YAML files and Payment/Order integration settings for dev, stage, and prod.");
    }

    private static boolean isYaml(Path path) {
        String name = path.getFileName().toString();
        return name.endsWith(".yml") || name.endsWith(".yaml");
    }

    private static void flatten(String file, String prefix, Map<?, ?> source, Map<String, Object> target) {
        for (Map.Entry<?, ?> entry : source.entrySet()) {
            if (!(entry.getKey() instanceof String key) || key.isBlank()) {
                error(file, "YAML mapping keys must be nonempty strings");
                continue;
            }
            String path = prefix.isEmpty() ? key : prefix + "." + key;
            if (entry.getValue() instanceof Map<?, ?> nested) {
                flatten(file, path, nested, target);
            } else if (target.containsKey(path)) {
                error(file, "duplicate property after resolving dotted YAML keys: " + path);
            } else {
                target.put(path, entry.getValue());
            }
        }
    }

    private static Map<String, Object> requireFile(Map<String, Map<String, Object>> configurations, String file) {
        Map<String, Object> values = configurations.get(file);
        if (values == null) {
            error(file, "required configuration file is missing or invalid");
            return Map.of();
        }
        return values;
    }

    private static void validatePayment(String file, Map<String, Object> values, String environment) {
        boolean development = environment.equals("dev");
        equal(file, values, "payment.order-lookup.base-url", development
                ? "${ORDER_SERVICE_URI:http://localhost:8086}" : "${ORDER_SERVICE_URI}");
        equal(file, values, "payment.order-lookup.secret", "${PAYMENT_ORDER_LOOKUP_SECRET}");
        Object poolSize = values.get("spring.task.scheduling.pool.size");
        if (!(poolSize instanceof Number number) || number.intValue() < 4) {
            error(file, "spring.task.scheduling.pool.size must be a checked-in integer of at least 4");
        }
        for (String key : WORKER_KEYS) {
            Object value = values.get("payment." + key);
            if (!(value instanceof Number) && !(value instanceof String text && text.matches("\\$\\{[A-Z0-9_]+:[1-9][0-9]*}"))) {
                error(file, "payment." + key + " must define a numeric setting or an environment placeholder with a positive default");
            } else if (value instanceof Number number && number.longValue() <= 0) {
                error(file, "payment." + key + " must be positive");
            }
        }
        equal(file, values, "payment.provider.active", "${PAYMENT_PROVIDER_ACTIVE:STRIPE}");
        equal(file, values, "payment.provider.mode", environment.equals("prod")
                ? "${PAYMENT_PROVIDER_MODE:live}" : "${PAYMENT_PROVIDER_MODE:test}");
        equal(file, values, "payment.provider.razorpay.enabled", false);
        equal(file, values, "payment.provider.sandbox.enabled", development ? "${PAYMENT_SANDBOX_ENABLED:true}" : false);
        equal(file, values, "payment.provider.stripe.enabled", "${STRIPE_ENABLED:true}");
        equal(file, values, "payment.provider.stripe.staging-verified", "${STRIPE_STAGING_VERIFIED:false}");
        equal(file, values, "payment.provider.stripe.api-key", "${STRIPE_API_KEY}");
        equal(file, values, "payment.provider.stripe.webhook-secret", "${STRIPE_WEBHOOK_SECRET}");
        equal(file, values, "payment.provider.stripe.previous-webhook-secrets", "${STRIPE_PREVIOUS_WEBHOOK_SECRETS:}");
        equal(file, values, "payment.checkout.allowed-provider-hosts", List.of("checkout.stripe.com"));
        equal(file, values, "payment.checkout.frontend-origins", development
                ? "${PAYMENT_FRONTEND_ORIGINS:http://localhost:5173}" : "${PAYMENT_FRONTEND_ORIGINS}");
        for (Map.Entry<String, String> binding : Map.of(
                "success-url", "PAYMENT_CHECKOUT_SUCCESS_URL", "cancel-url", "PAYMENT_CHECKOUT_CANCEL_URL",
                "frontend-return-url", "PAYMENT_FRONTEND_RETURN_URL").entrySet()) {
            String key = "payment.checkout." + binding.getKey();
            if (development) {
                Object value = values.get(key);
                if (!(value instanceof String text) || !text.startsWith("${" + binding.getValue() + ":http://localhost:5173/payment/return") || !text.endsWith("}")) {
                    error(file, key + " must use its documented development environment binding");
                }
            } else {
                equal(file, values, key, "${" + binding.getValue() + "}");
            }
        }
        equal(file, values, "spring.kafka.consumer.value-deserializer", "org.springframework.kafka.support.serializer.ErrorHandlingDeserializer");
        equal(file, values, "spring.kafka.consumer.properties.spring.deserializer.value.delegate.class", "org.springframework.kafka.support.serializer.JsonDeserializer");
        equal(file, values, "spring.kafka.consumer.properties.spring.json.use.type.headers", false);
    }

    private static void validateOrder(String file, Map<String, Object> values) {
        equal(file, values, "order.payment-lookup.secret", "${PAYMENT_ORDER_LOOKUP_SECRET}");
        for (Map.Entry<String, Object> entry : values.entrySet()) {
            String key = entry.getKey();
            if (key.equals("logging.payment-lookup") || key.startsWith("logging.payment-lookup.")) {
                error(file, "payment-lookup must be under order, not logging");
            }
            if (key.equals("logging.level")) {
                error(file, "logging.level must be a map of logger names to log levels");
            } else if (key.startsWith("logging.level.")) {
                Object value = entry.getValue();
                if (!(value instanceof String text) || !(LOG_LEVELS.contains(text) || text.matches("\\$\\{[A-Z0-9_]+:(TRACE|DEBUG|INFO|WARN|ERROR|FATAL|OFF)}"))) {
                    error(file, key + " must define a valid log level");
                }
            }
        }
    }

    private static void equal(String file, Map<String, Object> values, String key, Object expected) {
        if (!expected.equals(values.get(key))) {
            error(file, key + " is missing or does not match the Payment/Order configuration contract");
        }
    }

    private static void error(String file, String message) {
        ERRORS.add(file + ": " + message);
    }
}
