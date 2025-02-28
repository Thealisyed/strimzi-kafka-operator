/*
 * Copyright Strimzi authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.strimzi.systemtest.security.oauth;

import io.strimzi.api.kafka.model.KafkaBridgeResources;
import io.strimzi.api.kafka.model.KafkaConnect;
import io.strimzi.api.kafka.model.KafkaMirrorMaker;
import io.strimzi.api.kafka.model.KafkaMirrorMaker2;
import io.strimzi.api.kafka.model.KafkaMirrorMaker2ClusterSpec;
import io.strimzi.api.kafka.model.KafkaMirrorMaker2ClusterSpecBuilder;
import io.strimzi.api.kafka.model.KafkaResources;
import io.strimzi.api.kafka.model.listener.arraylistener.GenericKafkaListenerBuilder;
import io.strimzi.api.kafka.model.listener.arraylistener.KafkaListenerType;
import io.strimzi.operator.common.model.Labels;
import io.strimzi.systemtest.Constants;
import io.strimzi.systemtest.annotations.FIPSNotSupported;
import io.strimzi.systemtest.annotations.ParallelTest;
import io.strimzi.systemtest.kafkaclients.internalClients.BridgeClients;
import io.strimzi.systemtest.kafkaclients.internalClients.BridgeClientsBuilder;
import io.strimzi.systemtest.kafkaclients.internalClients.KafkaOauthClients;
import io.strimzi.systemtest.kafkaclients.internalClients.KafkaOauthClientsBuilder;
import io.strimzi.systemtest.storage.TestStorage;
import io.strimzi.systemtest.templates.crd.KafkaBridgeTemplates;
import io.strimzi.systemtest.templates.crd.KafkaConnectTemplates;
import io.strimzi.systemtest.templates.crd.KafkaMirrorMaker2Templates;
import io.strimzi.systemtest.templates.crd.KafkaMirrorMakerTemplates;
import io.strimzi.systemtest.templates.crd.KafkaTemplates;
import io.strimzi.systemtest.templates.crd.KafkaTopicTemplates;
import io.strimzi.systemtest.utils.ClientUtils;
import io.strimzi.systemtest.utils.kafkaUtils.KafkaConnectUtils;
import io.strimzi.systemtest.utils.kafkaUtils.KafkaConnectorUtils;
import io.strimzi.systemtest.utils.kubeUtils.controllers.JobUtils;
import io.strimzi.systemtest.utils.kubeUtils.objects.SecretUtils;
import io.strimzi.test.TestUtils;
import io.strimzi.test.WaitException;
import io.strimzi.test.k8s.KubeClusterResource;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.extension.ExtensionContext;

import java.time.Duration;

import static io.strimzi.systemtest.Constants.ARM64_UNSUPPORTED;
import static io.strimzi.systemtest.Constants.BRIDGE;
import static io.strimzi.systemtest.Constants.CONNECT;
import static io.strimzi.systemtest.Constants.CONNECT_COMPONENTS;
import static io.strimzi.systemtest.Constants.HTTP_BRIDGE_DEFAULT_PORT;
import static io.strimzi.systemtest.Constants.MIRROR_MAKER;
import static io.strimzi.systemtest.Constants.MIRROR_MAKER2;
import static io.strimzi.systemtest.Constants.OAUTH;
import static io.strimzi.systemtest.Constants.REGRESSION;
import static io.strimzi.test.k8s.KubeClusterResource.kubeClient;

@Tag(OAUTH)
@Tag(REGRESSION)
@Tag(ARM64_UNSUPPORTED)
@FIPSNotSupported("Keycloak is not customized to run on FIPS env - https://github.com/strimzi/strimzi-kafka-operator/issues/8331")
public class OauthPasswordGrantsST extends OauthAbstractST {
    protected static final Logger LOGGER = LogManager.getLogger(OauthAuthorizationST.class);
    private final String oauthClusterName = "oauth-pass-grants-cluster-name";
    private static final String TEST_REALM = "internal";
    private static final String ALICE_SECRET = "alice-secret";
    private static final String ALICE_USERNAME = "alice";
    private static final String ALICE_PASSWORD_KEY = "password";

    @ParallelTest
    @Tag(MIRROR_MAKER)
    void testPasswordGrantsKafkaMirrorMaker(ExtensionContext extensionContext) {
        final TestStorage testStorage = new TestStorage(extensionContext);

        KafkaOauthClients oauthExampleClients = new KafkaOauthClientsBuilder()
            .withNamespaceName(Constants.TEST_SUITE_NAMESPACE)
            .withProducerName(testStorage.getProducerName())
            .withConsumerName(testStorage.getConsumerName())
            .withBootstrapAddress(KafkaResources.plainBootstrapAddress(oauthClusterName))
            .withTopicName(testStorage.getTopicName())
            .withMessageCount(MESSAGE_COUNT)
            .withOauthClientId(OAUTH_CLIENT_NAME)
            .withOauthClientSecret(OAUTH_CLIENT_SECRET)
            .withOauthTokenEndpointUri(keycloakInstance.getOauthTokenEndpointUri())
            .build();

        resourceManager.createResourceWithWait(extensionContext, KafkaTopicTemplates.topic(oauthClusterName, testStorage.getTopicName(), Constants.TEST_SUITE_NAMESPACE).build());
        resourceManager.createResourceWithWait(extensionContext, oauthExampleClients.producerStrimziOauthPlain());
        ClientUtils.waitForClientSuccess(testStorage.getProducerName(), Constants.TEST_SUITE_NAMESPACE, MESSAGE_COUNT);

        resourceManager.createResourceWithWait(extensionContext, oauthExampleClients.consumerStrimziOauthPlain());
        ClientUtils.waitForClientSuccess(testStorage.getConsumerName(), Constants.TEST_SUITE_NAMESPACE, MESSAGE_COUNT);

        resourceManager.createResourceWithWait(extensionContext, KafkaTemplates.kafkaEphemeral(testStorage.getTargetClusterName(), 1, 1)
            .editMetadata()
                .withNamespace(Constants.TEST_SUITE_NAMESPACE)
            .endMetadata()
            .editSpec()
                .editKafka()
                    .withListeners(new GenericKafkaListenerBuilder()
                            .withName(Constants.PLAIN_LISTENER_DEFAULT_NAME)
                            .withPort(9092)
                            .withType(KafkaListenerType.INTERNAL)
                            .withTls(false)
                            .withNewKafkaListenerAuthenticationOAuth()
                                .withValidIssuerUri(keycloakInstance.getValidIssuerUri())
                                .withJwksEndpointUri(keycloakInstance.getJwksEndpointUri())
                                .withJwksExpirySeconds(keycloakInstance.getJwksExpireSeconds())
                                .withJwksRefreshSeconds(keycloakInstance.getJwksRefreshSeconds())
                                .withUserNameClaim(keycloakInstance.getUserNameClaim())
                            .endKafkaListenerAuthenticationOAuth()
                            .build())
                .endKafka()
            .endSpec()
            .build());

        resourceManager.createResourceWithWait(extensionContext, KafkaMirrorMakerTemplates.kafkaMirrorMaker(oauthClusterName, oauthClusterName, testStorage.getTargetClusterName(),
                ClientUtils.generateRandomConsumerGroup(), 1, false)
            .editMetadata()
                .withNamespace(Constants.TEST_SUITE_NAMESPACE)
            .endMetadata()
            .editSpec()
                .withNewConsumer()
                    .withBootstrapServers(KafkaResources.plainBootstrapAddress(oauthClusterName))
                    .withGroupId(ClientUtils.generateRandomConsumerGroup())
                    .addToConfig(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest")
                    .withNewKafkaClientAuthenticationOAuth()
                        .withTokenEndpointUri(keycloakInstance.getOauthTokenEndpointUri())
                        .withClientId(OAUTH_MM_CLIENT_ID)
                        .withUsername(ALICE_USERNAME)
                        .withNewPasswordSecret()
                            .withSecretName(ALICE_SECRET)
                            .withPassword(ALICE_PASSWORD_KEY)
                        .endPasswordSecret()
                        .withNewClientSecret()
                            .withSecretName(MIRROR_MAKER_OAUTH_SECRET)
                            .withKey(OAUTH_KEY)
                        .endClientSecret()
                        .withConnectTimeoutSeconds(CONNECT_TIMEOUT_S)
                        .withReadTimeoutSeconds(READ_TIMEOUT_S)
                    .endKafkaClientAuthenticationOAuth()
                    .withTls(null)
                .endConsumer()
                .withNewProducer()
                    .withBootstrapServers(KafkaResources.plainBootstrapAddress(testStorage.getTargetClusterName()))
                    .withNewKafkaClientAuthenticationOAuth()
                        .withTokenEndpointUri(keycloakInstance.getOauthTokenEndpointUri())
                        .withClientId(OAUTH_MM_CLIENT_ID)
                        .withUsername(ALICE_USERNAME)
                        .withNewPasswordSecret()
                            .withSecretName(ALICE_SECRET)
                            .withPassword(ALICE_PASSWORD_KEY)
                        .endPasswordSecret()
                        .withNewClientSecret()
                            .withSecretName(MIRROR_MAKER_OAUTH_SECRET)
                            .withKey(OAUTH_KEY)
                        .endClientSecret()
                        .withConnectTimeoutSeconds(CONNECT_TIMEOUT_S)
                        .withReadTimeoutSeconds(READ_TIMEOUT_S)
                    .endKafkaClientAuthenticationOAuth()
                    .addToConfig(ProducerConfig.ACKS_CONFIG, "all")
                    .withTls(null)
                .endProducer()
            .endSpec()
            .build());

        final String kafkaMirrorMakerPodName = kubeClient().listPods(Constants.TEST_SUITE_NAMESPACE, oauthClusterName, Labels.STRIMZI_KIND_LABEL, KafkaMirrorMaker.RESOURCE_KIND).get(0).getMetadata().getName();
        final String kafkaMirrorMakerLogs = KubeClusterResource.cmdKubeClient(Constants.TEST_SUITE_NAMESPACE).execInCurrentNamespace(Level.DEBUG, "logs", kafkaMirrorMakerPodName).out();
        verifyOauthConfiguration(kafkaMirrorMakerLogs);

        TestUtils.waitFor("MirrorMaker to copy messages from " + oauthClusterName + " to " + testStorage.getTargetClusterName(),
            Constants.GLOBAL_CLIENTS_POLL, Constants.TIMEOUT_FOR_MIRROR_MAKER_COPY_MESSAGES_BETWEEN_BROKERS,
            () -> {
                LOGGER.info("Deleting the Job");
                JobUtils.deleteJobWithWait(Constants.TEST_SUITE_NAMESPACE, OAUTH_CONSUMER_NAME);

                LOGGER.info("Creating new client with new consumer-group and also to point on {} cluster", testStorage.getTargetClusterName());
                KafkaOauthClients kafkaOauthClientJob = new KafkaOauthClientsBuilder()
                    .withNamespaceName(Constants.TEST_SUITE_NAMESPACE)
                    .withProducerName(testStorage.getConsumerName())
                    .withConsumerName(OAUTH_CONSUMER_NAME)
                    .withBootstrapAddress(KafkaResources.plainBootstrapAddress(testStorage.getTargetClusterName()))
                    .withTopicName(testStorage.getTopicName())
                    .withMessageCount(MESSAGE_COUNT)
                    .withOauthClientId(OAUTH_CLIENT_NAME)
                    .withOauthClientSecret(OAUTH_CLIENT_SECRET)
                    .withOauthTokenEndpointUri(keycloakInstance.getOauthTokenEndpointUri())
                    .build();

                resourceManager.createResourceWithWait(extensionContext, kafkaOauthClientJob.consumerStrimziOauthPlain());

                try {
                    ClientUtils.waitForClientSuccess(OAUTH_CONSUMER_NAME, Constants.TEST_SUITE_NAMESPACE, MESSAGE_COUNT);
                    return  true;
                } catch (WaitException e) {
                    e.printStackTrace();
                    return false;
                }
            });
    }

    @ParallelTest
    @Tag(MIRROR_MAKER2)
    void testPasswordGrantsKafkaMirrorMaker2(ExtensionContext extensionContext) {
        final TestStorage testStorage = new TestStorage(extensionContext);

        KafkaOauthClients oauthExampleClients = new KafkaOauthClientsBuilder()
            .withNamespaceName(Constants.TEST_SUITE_NAMESPACE)
            .withProducerName(testStorage.getProducerName())
            .withConsumerName(testStorage.getConsumerName())
            .withBootstrapAddress(KafkaResources.plainBootstrapAddress(oauthClusterName))
            .withTopicName(testStorage.getTopicName())
            .withMessageCount(MESSAGE_COUNT)
            .withOauthClientId(OAUTH_CLIENT_NAME)
            .withOauthClientSecret(OAUTH_CLIENT_SECRET)
            .withOauthTokenEndpointUri(keycloakInstance.getOauthTokenEndpointUri())
            .build();

        resourceManager.createResourceWithWait(extensionContext, KafkaTopicTemplates.topic(oauthClusterName, testStorage.getTopicName(), Constants.TEST_SUITE_NAMESPACE).build());
        resourceManager.createResourceWithWait(extensionContext, oauthExampleClients.producerStrimziOauthPlain());
        ClientUtils.waitForClientSuccess(testStorage.getProducerName(), Constants.TEST_SUITE_NAMESPACE, MESSAGE_COUNT);

        resourceManager.createResourceWithWait(extensionContext, oauthExampleClients.consumerStrimziOauthPlain());
        ClientUtils.waitForClientSuccess(testStorage.getConsumerName(), Constants.TEST_SUITE_NAMESPACE, MESSAGE_COUNT);

        String kafkaSourceClusterName = oauthClusterName;
        String kafkaTargetClusterName = testStorage.getClusterName() + "-target";
        // MirrorMaker2 adding prefix to mirrored Topic for in this case mirror Topic will be : my-cluster.my-topic
        String kafkaTargetClusterTopicName = kafkaSourceClusterName + "." + testStorage.getTopicName();

        resourceManager.createResourceWithWait(extensionContext, KafkaTemplates.kafkaEphemeral(kafkaTargetClusterName, 1, 1)
            .editMetadata()
                .withNamespace(Constants.TEST_SUITE_NAMESPACE)
            .endMetadata()
            .editSpec()
                .editKafka()
                    .withListeners(new GenericKafkaListenerBuilder()
                            .withName(Constants.PLAIN_LISTENER_DEFAULT_NAME)
                            .withPort(9092)
                            .withType(KafkaListenerType.INTERNAL)
                            .withTls(false)
                            .withNewKafkaListenerAuthenticationOAuth()
                                .withValidIssuerUri(keycloakInstance.getValidIssuerUri())
                                .withJwksEndpointUri(keycloakInstance.getJwksEndpointUri())
                                .withJwksExpirySeconds(keycloakInstance.getJwksExpireSeconds())
                                .withJwksRefreshSeconds(keycloakInstance.getJwksRefreshSeconds())
                                .withUserNameClaim(keycloakInstance.getUserNameClaim())
                            .endKafkaListenerAuthenticationOAuth()
                            .build())
                .endKafka()
            .endSpec()
            .build());

        // Deploy MirrorMaker2 with OAuth
        KafkaMirrorMaker2ClusterSpec sourceClusterWithOauth = new KafkaMirrorMaker2ClusterSpecBuilder()
            .withAlias(kafkaSourceClusterName)
            .withConfig(connectorConfig)
            .withBootstrapServers(KafkaResources.plainBootstrapAddress(kafkaSourceClusterName))
            .withNewKafkaClientAuthenticationOAuth()
                .withTokenEndpointUri(keycloakInstance.getOauthTokenEndpointUri())
                .withClientId(OAUTH_MM2_CLIENT_ID)
                .withUsername(ALICE_USERNAME)
                .withNewPasswordSecret()
                    .withSecretName(ALICE_SECRET)
                    .withPassword(ALICE_PASSWORD_KEY)
                .endPasswordSecret()
                .withNewClientSecret()
                    .withSecretName(MIRROR_MAKER_2_OAUTH_SECRET)
                    .withKey(OAUTH_KEY)
                .endClientSecret()
                .withConnectTimeoutSeconds(CONNECT_TIMEOUT_S)
                .withReadTimeoutSeconds(READ_TIMEOUT_S)
            .endKafkaClientAuthenticationOAuth()
            .build();

        KafkaMirrorMaker2ClusterSpec targetClusterWithOauth = new KafkaMirrorMaker2ClusterSpecBuilder()
            .withAlias(kafkaTargetClusterName)
            .withConfig(connectorConfig)
            .withBootstrapServers(KafkaResources.plainBootstrapAddress(kafkaTargetClusterName))
            .withNewKafkaClientAuthenticationOAuth()
                .withTokenEndpointUri(keycloakInstance.getOauthTokenEndpointUri())
                .withClientId(OAUTH_MM2_CLIENT_ID)
                .withUsername(ALICE_USERNAME)
                .withNewPasswordSecret()
                    .withSecretName(ALICE_SECRET)
                    .withPassword(ALICE_PASSWORD_KEY)
                .endPasswordSecret()
                .withNewClientSecret()
                    .withSecretName(MIRROR_MAKER_2_OAUTH_SECRET)
                    .withKey(OAUTH_KEY)
                .endClientSecret()
                .withConnectTimeoutSeconds(CONNECT_TIMEOUT_S)
                .withReadTimeoutSeconds(READ_TIMEOUT_S)
            .endKafkaClientAuthenticationOAuth()
            .build();

        resourceManager.createResourceWithWait(extensionContext, KafkaMirrorMaker2Templates.kafkaMirrorMaker2(oauthClusterName, kafkaTargetClusterName, kafkaSourceClusterName, 1, false)
            .editMetadata()
                .withNamespace(Constants.TEST_SUITE_NAMESPACE)
            .endMetadata()
            .editSpec()
                .withClusters(sourceClusterWithOauth, targetClusterWithOauth)
                .editFirstMirror()
                    .withSourceCluster(kafkaSourceClusterName)
                .endMirror()
            .endSpec()
            .build());

        final String kafkaMirrorMaker2PodName = kubeClient().listPods(Constants.TEST_SUITE_NAMESPACE, oauthClusterName, Labels.STRIMZI_KIND_LABEL, KafkaMirrorMaker2.RESOURCE_KIND).get(0).getMetadata().getName();
        final String kafkaMirrorMaker2Logs = KubeClusterResource.cmdKubeClient(Constants.TEST_SUITE_NAMESPACE).execInCurrentNamespace(Level.DEBUG, "logs", kafkaMirrorMaker2PodName).out();
        verifyOauthConfiguration(kafkaMirrorMaker2Logs);

        TestUtils.waitFor("MirrorMaker2 to copy messages from " + kafkaSourceClusterName + " to " + kafkaTargetClusterName,
            Duration.ofSeconds(30).toMillis(), Constants.TIMEOUT_FOR_MIRROR_MAKER_COPY_MESSAGES_BETWEEN_BROKERS,
            () -> {
                LOGGER.info("Deleting Job: {}/{}", Constants.TEST_SUITE_NAMESPACE, testStorage.getConsumerName());
                JobUtils.deleteJobWithWait(Constants.TEST_SUITE_NAMESPACE, testStorage.getClusterName());

                LOGGER.info("Creating new client with new consumer group and also to point on {} cluster", kafkaTargetClusterName);

                KafkaOauthClients kafkaOauthClientJob = new KafkaOauthClientsBuilder()
                    .withNamespaceName(Constants.TEST_SUITE_NAMESPACE)
                    .withProducerName(testStorage.getProducerName())
                    .withConsumerName(testStorage.getConsumerName())
                    .withBootstrapAddress(KafkaResources.plainBootstrapAddress(kafkaTargetClusterName))
                    .withTopicName(kafkaTargetClusterTopicName)
                    .withMessageCount(MESSAGE_COUNT)
                    .withOauthClientId(OAUTH_CLIENT_NAME)
                    .withOauthClientSecret(OAUTH_CLIENT_SECRET)
                    .withOauthTokenEndpointUri(keycloakInstance.getOauthTokenEndpointUri())
                    .build();

                resourceManager.createResourceWithWait(extensionContext, kafkaOauthClientJob.consumerStrimziOauthPlain());

                try {
                    ClientUtils.waitForClientSuccess(testStorage.getConsumerName(), Constants.TEST_SUITE_NAMESPACE, MESSAGE_COUNT);
                    return  true;
                } catch (WaitException e) {
                    e.printStackTrace();
                    return false;
                }
            });
    }

    @ParallelTest
    @Tag(CONNECT)
    @Tag(CONNECT_COMPONENTS)
    void testPasswordGrantsKafkaConnect(ExtensionContext extensionContext) {
        final TestStorage testStorage = new TestStorage(extensionContext);

        KafkaOauthClients oauthExampleClients = new KafkaOauthClientsBuilder()
            .withNamespaceName(Constants.TEST_SUITE_NAMESPACE)
            .withProducerName(testStorage.getProducerName())
            .withConsumerName(testStorage.getConsumerName())
            .withBootstrapAddress(KafkaResources.plainBootstrapAddress(oauthClusterName))
            .withTopicName(testStorage.getTopicName())
            .withMessageCount(MESSAGE_COUNT)
            .withOauthClientId(OAUTH_CLIENT_NAME)
            .withOauthClientSecret(OAUTH_CLIENT_SECRET)
            .withOauthTokenEndpointUri(keycloakInstance.getOauthTokenEndpointUri())
            .build();

        resourceManager.createResourceWithWait(extensionContext, KafkaTopicTemplates.topic(oauthClusterName, testStorage.getTopicName(), Constants.TEST_SUITE_NAMESPACE).build());
        resourceManager.createResourceWithWait(extensionContext, oauthExampleClients.producerStrimziOauthPlain());
        ClientUtils.waitForClientSuccess(testStorage.getProducerName(), Constants.TEST_SUITE_NAMESPACE, MESSAGE_COUNT);

        resourceManager.createResourceWithWait(extensionContext, oauthExampleClients.consumerStrimziOauthPlain());
        ClientUtils.waitForClientSuccess(testStorage.getConsumerName(), Constants.TEST_SUITE_NAMESPACE, MESSAGE_COUNT);

        KafkaConnect connect = KafkaConnectTemplates.kafkaConnectWithFilePlugin(testStorage.getClusterName(), Constants.TEST_SUITE_NAMESPACE, oauthClusterName, 1)
            .editMetadata()
                .withNamespace(Constants.TEST_SUITE_NAMESPACE)
            .endMetadata()
            .editOrNewSpec()
                .withBootstrapServers(KafkaResources.plainBootstrapAddress(oauthClusterName))
                .withConfig(connectorConfig)
                .addToConfig("key.converter.schemas.enable", false)
                .addToConfig("value.converter.schemas.enable", false)
                .addToConfig("key.converter", "org.apache.kafka.connect.storage.StringConverter")
                .addToConfig("value.converter", "org.apache.kafka.connect.storage.StringConverter")
                .withNewKafkaClientAuthenticationOAuth()
                    .withTokenEndpointUri(keycloakInstance.getOauthTokenEndpointUri())
                    .withClientId(OAUTH_CONNECT_CLIENT_ID)
                    .withUsername(ALICE_USERNAME)
                    .withNewPasswordSecret()
                        .withSecretName(ALICE_SECRET)
                        .withPassword(ALICE_PASSWORD_KEY)
                    .endPasswordSecret()
                    .withNewClientSecret()
                        .withSecretName(CONNECT_OAUTH_SECRET)
                        .withKey(OAUTH_KEY)
                    .endClientSecret()
                    .withConnectTimeoutSeconds(CONNECT_TIMEOUT_S)
                    .withReadTimeoutSeconds(READ_TIMEOUT_S)
                .endKafkaClientAuthenticationOAuth()
                .withNewInlineLogging()
                    // needed for a verification of oauth configuration
                    .addToLoggers("connect.root.logger.level", "DEBUG")
                .endInlineLogging()
            .endSpec()
            .build();
        // This is required to be able to remove the TLS setting, the builder cannot remove it
        connect.getSpec().setTls(null);

        resourceManager.createResourceWithWait(extensionContext, connect);

        final String kafkaConnectPodName = kubeClient().listPods(Constants.TEST_SUITE_NAMESPACE, testStorage.getClusterName(), Labels.STRIMZI_KIND_LABEL, KafkaConnect.RESOURCE_KIND).get(0).getMetadata().getName();

        KafkaConnectUtils.waitUntilKafkaConnectRestApiIsAvailable(Constants.TEST_SUITE_NAMESPACE, kafkaConnectPodName);

        KafkaConnectorUtils.createFileSinkConnector(Constants.TEST_SUITE_NAMESPACE, kafkaConnectPodName, testStorage.getTopicName(), Constants.DEFAULT_SINK_FILE_PATH, "http://localhost:8083");

        KafkaConnectUtils.waitForMessagesInKafkaConnectFileSink(Constants.TEST_SUITE_NAMESPACE, kafkaConnectPodName, Constants.DEFAULT_SINK_FILE_PATH, "\"Hello-world - 99\"");

        final String kafkaConnectLogs = KubeClusterResource.cmdKubeClient(Constants.TEST_SUITE_NAMESPACE).execInCurrentNamespace(Level.DEBUG, "logs", kafkaConnectPodName).out();
        verifyOauthConfiguration(kafkaConnectLogs);
    }

    @ParallelTest
    @Tag(BRIDGE)
    void testPasswordGrantsKafkaBridge(ExtensionContext extensionContext) {
        final TestStorage testStorage = new TestStorage(extensionContext);

        resourceManager.createResourceWithWait(extensionContext, KafkaTopicTemplates.topic(oauthClusterName, testStorage.getTopicName(), Constants.TEST_SUITE_NAMESPACE).build());

        KafkaOauthClients oauthExampleClients = new KafkaOauthClientsBuilder()
            .withNamespaceName(Constants.TEST_SUITE_NAMESPACE)
            .withProducerName(testStorage.getProducerName())
            .withConsumerName(testStorage.getConsumerName())
            .withBootstrapAddress(KafkaResources.plainBootstrapAddress(oauthClusterName))
            .withTopicName(testStorage.getTopicName())
            .withMessageCount(MESSAGE_COUNT)
            .withOauthClientId(OAUTH_CLIENT_NAME)
            .withOauthClientSecret(OAUTH_CLIENT_SECRET)
            .withOauthTokenEndpointUri(keycloakInstance.getOauthTokenEndpointUri())
            .build();

        resourceManager.createResourceWithWait(extensionContext, oauthExampleClients.producerStrimziOauthPlain());
        ClientUtils.waitForClientSuccess(testStorage.getProducerName(), Constants.TEST_SUITE_NAMESPACE, MESSAGE_COUNT);

        resourceManager.createResourceWithWait(extensionContext, oauthExampleClients.consumerStrimziOauthPlain());
        ClientUtils.waitForClientSuccess(testStorage.getConsumerName(), Constants.TEST_SUITE_NAMESPACE, MESSAGE_COUNT);

        resourceManager.createResourceWithWait(extensionContext, KafkaBridgeTemplates.kafkaBridge(oauthClusterName, KafkaResources.plainBootstrapAddress(oauthClusterName), 1)
            .editMetadata()
                .withNamespace(Constants.TEST_SUITE_NAMESPACE)
            .endMetadata()
            .editSpec()
                .withNewKafkaClientAuthenticationOAuth()
                    .withTokenEndpointUri(keycloakInstance.getOauthTokenEndpointUri())
                    .withClientId(OAUTH_BRIDGE_CLIENT_ID)
                    .withUsername(ALICE_USERNAME)
                    .withNewPasswordSecret()
                        .withSecretName(ALICE_SECRET)
                        .withPassword(ALICE_PASSWORD_KEY)
                    .endPasswordSecret()
                    .withNewClientSecret()
                        .withSecretName(BRIDGE_OAUTH_SECRET)
                        .withKey(OAUTH_KEY)
                    .endClientSecret()
                .endKafkaClientAuthenticationOAuth()
            .endSpec()
            .build());

        String producerName = "bridge-producer-" + testStorage.getClusterName();

        BridgeClients kafkaBridgeClientJob = new BridgeClientsBuilder()
            .withProducerName(producerName)
            .withBootstrapAddress(KafkaBridgeResources.serviceName(oauthClusterName))
            .withTopicName(testStorage.getTopicName())
            .withMessageCount(MESSAGE_COUNT)
            .withPort(HTTP_BRIDGE_DEFAULT_PORT)
            .withDelayMs(1000)
            .withPollInterval(1000)
            .withNamespaceName(Constants.TEST_SUITE_NAMESPACE)
            .build();

        resourceManager.createResourceWithWait(extensionContext, kafkaBridgeClientJob.producerStrimziBridge());
        ClientUtils.waitForClientSuccess(producerName, Constants.TEST_SUITE_NAMESPACE, MESSAGE_COUNT);
    }

    @BeforeAll
    void setUp(ExtensionContext extensionContext)  {
        super.setupCoAndKeycloak(extensionContext, Constants.TEST_SUITE_NAMESPACE);

        keycloakInstance.setRealm(TEST_REALM, false);

        LOGGER.info("Keycloak settings {}", keycloakInstance.toString());

        resourceManager.createResourceWithWait(extensionContext, KafkaTemplates.kafkaEphemeral(oauthClusterName, 3)
            .editMetadata()
                .withNamespace(Constants.TEST_SUITE_NAMESPACE)
            .endMetadata()
            .editSpec()
                .editKafka()
                    .withListeners(new GenericKafkaListenerBuilder()
                        .withName(Constants.PLAIN_LISTENER_DEFAULT_NAME)
                        .withPort(9092)
                        .withType(KafkaListenerType.INTERNAL)
                        .withTls(false)
                        .withNewKafkaListenerAuthenticationOAuth()
                            .withValidIssuerUri(keycloakInstance.getValidIssuerUri())
                            .withJwksExpirySeconds(keycloakInstance.getJwksExpireSeconds())
                            .withJwksRefreshSeconds(keycloakInstance.getJwksRefreshSeconds())
                            .withJwksEndpointUri(keycloakInstance.getJwksEndpointUri())
                            .withUserNameClaim(keycloakInstance.getUserNameClaim())
                            .withEnablePlain(true)
                            .withTokenEndpointUri(keycloakInstance.getOauthTokenEndpointUri())
                            .withGroupsClaim(GROUPS_CLAIM)
                            .withGroupsClaimDelimiter(GROUPS_CLAIM_DELIMITER)
                        .endKafkaListenerAuthenticationOAuth()
                        .build())
                .endKafka()
            .endSpec()
            .build());

        SecretUtils.createSecret(Constants.TEST_SUITE_NAMESPACE, ALICE_SECRET, ALICE_PASSWORD_KEY, "alice-password");
    }
}
