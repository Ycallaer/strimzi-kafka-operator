/*
 * Copyright Strimzi authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.strimzi.systemtest.utils.kubeUtils.objects;

import io.fabric8.kubernetes.api.model.Secret;
import io.fabric8.kubernetes.api.model.SecretBuilder;
import io.strimzi.operator.common.model.Labels;
import io.strimzi.systemtest.Constants;
import io.strimzi.systemtest.resources.ResourceManager;
import io.strimzi.systemtest.resources.ResourceOperation;
import io.strimzi.systemtest.security.CertAndKeyFiles;
import io.strimzi.test.TestUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.security.cert.CertificateException;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.util.Base64;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

import static io.strimzi.test.k8s.KubeClusterResource.kubeClient;

public class SecretUtils {

    private static final Logger LOGGER = LogManager.getLogger(SecretUtils.class);
    private static final long READINESS_TIMEOUT = ResourceOperation.getTimeoutForResourceReadiness(Constants.SECRET);
    private static final long DELETION_TIMEOUT = ResourceOperation.getTimeoutForResourceDeletion();

    private SecretUtils() { }

    public static void waitForSecretReady(String namespaceName, String secretName, Runnable onTimeout) {
        LOGGER.info("Waiting for Secret {}/{}", namespaceName, secretName);
        TestUtils.waitFor("Expected secret " + secretName + " exists", Constants.POLL_INTERVAL_FOR_RESOURCE_READINESS, READINESS_TIMEOUT,
            () -> kubeClient(namespaceName).getSecret(namespaceName, secretName) != null,
            onTimeout);
        LOGGER.info("Secret {}/{} created", namespaceName, secretName);
    }

    public static void waitForSecretDeletion(final String namespaceName, String secretName, Runnable onTimeout) {
        LOGGER.info("Waiting for Secret {}/{} deletion", namespaceName, secretName);
        TestUtils.waitFor("secret " + namespaceName + "/" + secretName + " deletion", Constants.POLL_INTERVAL_FOR_RESOURCE_READINESS, READINESS_TIMEOUT,
            () -> kubeClient(namespaceName).getSecret(namespaceName, secretName) == null,
            onTimeout);
        LOGGER.info("Secret {}/{} deleted", namespaceName, secretName);
    }

    public static void waitForSecretDeletion(final String namespaceName, String secretName) {
        waitForSecretDeletion(namespaceName, secretName, () -> { });
    }

    public static void createSecret(String namespaceName, String secretName, String dataKey, String dataValue) {
        LOGGER.info("Creating secret {}", secretName);
        kubeClient(namespaceName).createSecret(new SecretBuilder()
            .withApiVersion("v1")
            .withKind("Secret")
            .withNewMetadata()
                .withName(secretName)
                .withNamespace(namespaceName)
            .endMetadata()
            .withType("Opaque")
                .withStringData(Collections.singletonMap(dataKey, dataValue))
            .build());
    }

    public static Secret createSecretFromFile(String pathToOrigin, String key, String name, String namespace, Map<String, String> labels) {
        return createSecretFromFile(Collections.singletonMap(key, pathToOrigin), name, namespace, labels);
    }

    public static Secret createSecretFromFile(Map<String, String> certFilesPath, String name, String namespace, Map<String, String> labels) {
        byte[] encoded;
        try {
            Map<String, String> data = new HashMap<>();
            for (Map.Entry<String, String> entry : certFilesPath.entrySet()) {
                encoded = Files.readAllBytes(Paths.get(entry.getValue()));

                Base64.Encoder encoder = Base64.getEncoder();
                data.put(entry.getKey(), encoder.encodeToString(encoded));
            }

            return new SecretBuilder()
                .withData(data)
                    .withNewMetadata()
                        .withName(name)
                        .withNamespace(namespace)
                        .addToLabels(labels)
                    .endMetadata()
                .build();
        } catch (IOException e) {
            e.printStackTrace();
            return null;
        }
    }

    public static SecretBuilder retrieveSecretBuilderFromFile(final Map<String, String> certFilesPath, final String name,
                                                              final String namespace, final Map<String, String> labels,
                                                              final String secretType) {
        byte[] encoded;
        final Map<String, String> data = new HashMap<>();

        try {
            for (final Map.Entry<String, String> entry : certFilesPath.entrySet()) {
                encoded = Files.readAllBytes(Paths.get(entry.getValue()));

                final Base64.Encoder encoder = Base64.getEncoder();
                data.put(entry.getKey(), encoder.encodeToString(encoded));
            }
        } catch (IOException e) {
            e.printStackTrace();
        }

        return new SecretBuilder()
            .withType(secretType)
            .withData(data)
                .withNewMetadata()
                .withName(name)
                .withNamespace(namespace)
                .addToLabels(labels)
            .endMetadata();
    }

    public static void createCustomSecret(String name, String clusterName, String namespace, CertAndKeyFiles certAndKeyFiles) {
        Map<String, String> secretLabels = new HashMap<>();
        secretLabels.put(Labels.STRIMZI_CLUSTER_LABEL, clusterName);
        secretLabels.put(Labels.STRIMZI_KIND_LABEL, "Kafka");

        Map<String, String> certsPaths = new HashMap<>();
        certsPaths.put("ca.crt", certAndKeyFiles.getCertPath());
        certsPaths.put("ca.key", certAndKeyFiles.getKeyPath());

        Secret secret = createSecretFromFile(certsPaths, name, namespace, secretLabels);
        kubeClient().namespace(namespace).createSecret(secret);
        waitForSecretReady(namespace, name, () -> { });
    }

    public static void updateCustomSecret(String name, String clusterName, String namespace, CertAndKeyFiles certAndKeyFiles) {
        Map<String, String> secretLabels = new HashMap<>();
        secretLabels.put(Labels.STRIMZI_CLUSTER_LABEL, clusterName);
        secretLabels.put(Labels.STRIMZI_KIND_LABEL, "Kafka");

        Map<String, String> certsPaths = new HashMap<>();
        certsPaths.put("ca.crt", certAndKeyFiles.getCertPath());
        certsPaths.put("ca.key", certAndKeyFiles.getKeyPath());

        Secret secret = createSecretFromFile(certsPaths, name, namespace, secretLabels);
        kubeClient().namespace(namespace).updateSecret(secret);
        waitForSecretReady(namespace, name, () -> { });
    }

    public static void waitForCertToChange(String namespaceName, String originalCert, String secretName) {
        LOGGER.info("Waiting for Secret {}/{} certificate change", namespaceName, secretName);
        TestUtils.waitFor("cert to be replaced", Constants.GLOBAL_POLL_INTERVAL, Constants.TIMEOUT_FOR_CLUSTER_STABLE, () -> {
            Secret secret = kubeClient(namespaceName).getSecret(namespaceName, secretName);
            if (secret != null && secret.getData() != null && secret.getData().containsKey("ca.crt")) {
                String currentCert = new String(Base64.getDecoder().decode(secret.getData().get("ca.crt")), StandardCharsets.US_ASCII);
                boolean changed = !originalCert.equals(currentCert);
                if (changed) {
                    LOGGER.info("Certificate in Secret {}/{} has changed, was {}, is now {}", namespaceName, secretName, originalCert, currentCert);
                }
                return changed;
            } else {
                return false;
            }
        });
    }

    public static void deleteSecretWithWait(String secretName, String namespace) {
        kubeClient().getClient().secrets().inNamespace(namespace).withName(secretName).delete();

        LOGGER.info("Waiting for Secret {}/{} to be deleted", namespace, secretName);
        TestUtils.waitFor(String.format("Deletion of Secret %s#%s", namespace, secretName), Constants.GLOBAL_POLL_INTERVAL, DELETION_TIMEOUT,
            () -> kubeClient().getSecret(namespace, secretName) == null);

        LOGGER.info("Secret {}/{} successfully deleted", namespace, secretName);
    }

    public static X509Certificate getCertificateFromSecret(Secret secret, String dataKey) {
        String caCert = secret.getData().get(dataKey);
        byte[] decoded = Base64.getDecoder().decode(caCert);
        X509Certificate cacert = null;
        try {
            cacert = (X509Certificate)
                    CertificateFactory.getInstance("X.509").generateCertificate(new ByteArrayInputStream(decoded));
        } catch (CertificateException e) {
            e.printStackTrace();
        }
        return cacert;
    }

    public static void waitForUserPasswordChange(String namespaceName, String secretName, String expectedEncodedPassword) {
        LOGGER.info("Waiting for user password will be changed to {} in secret {}/{}", expectedEncodedPassword, namespaceName, secretName);
        TestUtils.waitFor(String.format("user password will be changed to: %s in secret: %s(%s)", expectedEncodedPassword, namespaceName, secretName),
            Constants.GLOBAL_POLL_INTERVAL, Constants.GLOBAL_TIMEOUT,
            () -> kubeClient().namespace(namespaceName).getSecret(secretName).getData().get("password").equals(expectedEncodedPassword));
    }

    public static String annotateSecret(String namespaceName, String secretName, String annotationKey, String annotationValue) {
        LOGGER.info("Annotating Secret {}/{} with annotation {}={}", namespaceName, secretName, annotationKey, annotationValue);
        return ResourceManager.cmdKubeClient().namespace(namespaceName)
                .execInCurrentNamespace("annotate", "secret", secretName, annotationKey + "=" + annotationValue)
                .out()
                .trim();
    }

    public static SecretBuilder createCopyOfSecret(String fromNamespace, String toNamespace, String secretName) {
        Secret desiredSecret = kubeClient().getSecret(fromNamespace, secretName);

        return new SecretBuilder(desiredSecret)
            .withNewMetadata()
                .withName(secretName)
                .withNamespace(toNamespace)
            .endMetadata();
    }

    /**
     * Method which waits for {@code secretName} Secret in {@code namespace} namespace to have
     * label with {@code labelKey} key and {@code labelValue} value.
     *
     * @param namespace name of namespace
     * @param secretName name of Secret
     * @param labelKey label key
     * @param labelValue label value
     */
    public static void waitForSpecificLabelKeyValue(String secretName, String namespace, String labelKey, String labelValue) {
        LOGGER.info("Wait for Secret: {}/{} (Corresponding to KafkaUser) to have expected label: {}={}", namespace, secretName, labelKey, labelValue);
        TestUtils.waitFor("Wait for the Secret {}: {}/{} to exist while also having label to corresponding Kafka cluster", Constants.GLOBAL_POLL_INTERVAL, Constants.GLOBAL_TIMEOUT,
            () -> kubeClient().listSecrets(namespace)
                .stream()
                .anyMatch(secret -> secretName.equals(secret.getMetadata().getName())
                    && secret.getMetadata().getLabels().containsKey(Labels.STRIMZI_CLUSTER_LABEL)
                    && secret.getMetadata().getLabels().get(Labels.STRIMZI_CLUSTER_LABEL).equals(labelValue)
                )
        );
    }
}
