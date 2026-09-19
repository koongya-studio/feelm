package com.feelm.backend.domain.recommendation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;

import com.feelm.backend.domain.recommendation.PersonalCandidateKafkaOffsetIntegrationTest.MappedKafkaContainer;
import com.github.dockerjava.api.command.InspectContainerResponse;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.testcontainers.images.builder.Transferable;

class PersonalCandidateKafkaContainerTest {
    @Test
    void readinessUsesOnlyThePortAssignedByDocker() {
        MappedKafkaContainer container = spy(new MappedKafkaContainer());
        doReturn(49173).when(container).getMappedPort(9092);

        assertThat(container.getPortBindings()).isEmpty();
        assertThat(container.getExposedPorts()).containsExactly(9092);
        assertThat(container.getLivenessCheckPortNumbers()).containsExactly(49173);
    }

    @Test
    void advertisesTheSameMappedAddressUsedByClientsAndKeepsBrokerTrafficInternal() {
        MappedKafkaContainer container = spy(new MappedKafkaContainer());
        doReturn("127.0.0.1").when(container).getHost();
        doReturn(49173).when(container).getMappedPort(9092);
        doNothing().when(container).copyFileToContainer(any(Transferable.class), anyString());

        container.containerIsStarting(new InspectContainerResponse());

        ArgumentCaptor<Transferable> script = ArgumentCaptor.forClass(Transferable.class);
        ArgumentCaptor<String> path = ArgumentCaptor.forClass(String.class);
        verify(container).copyFileToContainer(script.capture(), path.capture());
        assertThat(container.getBootstrapServers()).isEqualTo("127.0.0.1:49173");
        assertThat(new String(script.getValue().getBytes(), StandardCharsets.UTF_8))
                .contains("PLAINTEXT://127.0.0.1:49173,BROKER://localhost:9094")
                .contains("exec /etc/kafka/docker/run");
        assertThat(container.getCommandParts()[2]).contains(path.getValue());
    }
}
